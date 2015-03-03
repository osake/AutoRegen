package nl.dykam.dev.autoregen;

import com.google.common.collect.Multimap;
import com.google.common.collect.TreeMultimap;
import com.mewin.WGCustomFlags.WGCustomFlagsPlugin;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import nl.dykam.dev.autoregen.regenerators.Regenerator;
import nl.dykam.dev.autoregen.regenerators.RegeneratorCreator;
import nl.dykam.dev.autoregen.regenerators.defaults.CropRegenerator;
import nl.dykam.dev.autoregen.regenerators.defaults.TallCropGenerator;
import nl.dykam.dev.autoregen.util.ConfigExtra;
import nl.dykam.dev.autoregen.util.TitleSettings;
import org.apache.commons.collections.ComparatorUtils;
import org.apache.commons.collections.ListUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoRegenPlugin extends JavaPlugin implements Listener {
    public static final TitleSettings TITLE_SETTINGS = new TitleSettings(20, 5);
    private static AutoRegenPlugin instance;
    private Toaster toaster;
    private WorldGuardPlugin worldGuard;
    private HashMap<UUID, Boolean> bypasses;
    private Map<String, RegenGroup> regenGroups;
    private Map<String, RegeneratorCreator> creators;
    private Random random;
    private Map<BukkitTask, Runnable> regenTasks;

    public static final StringFlag AUTOREGEN = new StringFlag("autoregen");
    public static final Pattern TIME_PATTERN = Pattern.compile("(\\d+)(s(?:ec(?:ond)?)?|m(:?in(?:ute)?)?|h(?:our)?|d(?:ay)?)?", Pattern.CASE_INSENSITIVE);

    @Override @SuppressWarnings("unchecked")
    public void onEnable() {
        super.onEnable();
        instance = this;
        regenGroups = new HashMap<>();
        bypasses = new HashMap<>();
        creators = new HashMap<>();
        random = new Random();
        regenTasks = new HashMap<>();

        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            toaster = new ChatToaster();
        } else {
            toaster = new ProtocolLibTitleToaster();
        }

        WGCustomFlagsPlugin customFlagsPlugin = setupCustomFlags();
        customFlagsPlugin.addCustomFlag(AUTOREGEN);

        worldGuard = WorldGuardPlugin.inst();
        getConfig().options().copyDefaults(true);

        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getServicesManager().register(RegeneratorCreator.class, new CropRegenerator.Creator(this), this, ServicePriority.Normal);
        Bukkit.getServicesManager().register(RegeneratorCreator.class, new TallCropGenerator.Creator(this), this, ServicePriority.Normal);

        for (RegisteredServiceProvider<RegeneratorCreator> provider : Bukkit.getServicesManager().getRegistrations(RegeneratorCreator.class)) {
            RegeneratorCreator creator = provider.getProvider();
            String creatorName = creator.getName().toLowerCase();
            String name = creator.getPlugin().getName().toLowerCase() + ":" + creatorName;
            creators.put(name, creator);
            if(!creators.containsKey(creatorName))
                creators.put(creatorName, creator);

            ConfigurationSection defaultConfiguration = creator.getDefaultConfiguration();
            if(defaultConfiguration != null)
                getConfig().addDefault("example-creators." + name, defaultConfiguration);
            else
                getConfig().addDefault("example-creators." + name, true);
        }

        parseConfig();
        saveConfig();
    }

    @Override
    public void onDisable() {
        getLogger().info("Running all outstanding regeneration tasks");
        for (BukkitTask bukkitTask : regenTasks.keySet()) {
            bukkitTask.cancel();
        }
        for (Runnable runnable : new ArrayList<>(regenTasks.values())) {
            runnable.run();
        }

    }

    private void parseConfig() {
        regenGroups.clear();
        ConfigurationSection groupsSection = getConfig().getConfigurationSection("groups");
        for (String groupName : groupsSection.getKeys(false)) {
            ConfigurationSection groupSection = groupsSection.getConfigurationSection(groupName);
            List<RegeneratorSet> regeneratorSets = new ArrayList<>();
            TimeRules defaultTimeRules = parseTimeRules(groupSection.getConfigurationSection("settings.timing"), TimeRules.INSTANT);
            boolean protect = groupSection.getBoolean("settings.protect", true);

            for (ConfigurationSection regeneratorsSection : ConfigExtra.getConfigList(groupSection, "sets")) {
                TimeRules timeRules = parseTimeRules(regeneratorsSection.getConfigurationSection("timing"), defaultTimeRules);
                for (String regeneratorName : regeneratorsSection.getKeys(false)) {
                    if(regeneratorName.equals("timing")) continue;

                    ConfigurationSection regeneratorConfig = regeneratorsSection.getConfigurationSection(regeneratorName);
                    if (regeneratorConfig == null && !regeneratorsSection.getBoolean(regeneratorName, false))
                        continue;

                    RegeneratorCreator regeneratorCreator = creators.get(regeneratorName);
                    if(regeneratorCreator == null) {
                        getLogger().warning("Unknown regenerator: " + regeneratorName);
                        continue;
                    }

                    Regenerator regenerator = regeneratorCreator.generate(regeneratorConfig);
                    regeneratorSets.add(new RegeneratorSet(timeRules, regenerator));
                }
            }

            RegenGroup regenGroup = new RegenGroup(groupName, regeneratorSets, protect);
            regenGroups.put(groupName, regenGroup);
        }
    }

    private TimeRules parseTimeRules(ConfigurationSection regen) {
        if(regen == null)
            return null;
        String string = regen.getString("type", "INSTANT");
        TimeRuleType type = TimeRuleType.DELAY;
        try {
            type = TimeRuleType.valueOf(string);
        } catch (IllegalArgumentException ignored) {}
        long time = string.equalsIgnoreCase("INSTANT") ? 0 : parseTime(regen.getString("time", "0"));
        return new TimeRules(/*parseThreshold(regen.getString("threshold", "0")), */time, type);
    }

    private TimeRules parseTimeRules(ConfigurationSection regen, TimeRules defaults) {
        TimeRules result = parseTimeRules(regen);
        return result != null ? result : defaults;
    }

    private Threshold parseThreshold(String threshold) {
        if(threshold.endsWith("%"))
            return new Threshold(Float.parseFloat(threshold.substring(0, threshold.length() - 1)), Threshold.Type.PERCENTAGE);
        else
            return new Threshold(Integer.parseInt(threshold), Threshold.Type.ABSOLUTE);
    }

    private long parseTime(String time) {
        Matcher matcher = TIME_PATTERN.matcher(time);
        Calendar calendar = Calendar.getInstance();
        Calendar now = Calendar.getInstance();
        while (matcher.find()) {
            String unit = matcher.group(2) == null ? "s" : matcher.group(2);
            Integer amount = Integer.parseInt(matcher.group(1));
            switch (unit.toLowerCase()) {
                case "s":case "sec":case "second":
                    calendar.add(Calendar.SECOND, amount);
                    break;
                case "m":case "min":case "minute":
                    calendar.add(Calendar.MINUTE, amount);
                    break;
                case "h":case "hour":
                    calendar.add(Calendar.HOUR, amount);
                    break;
                case "d": case "day":
                    calendar.add(Calendar.MONTH, amount);
                    break;
            }
        }
        return calendar.getTimeInMillis() - now.getTimeInMillis();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getLabel()) {
            case "autoregenreload":
                reloadConfig();
                parseConfig();
                sender.sendMessage(ChatColor.DARK_GREEN + "Reloaded Config");
                break;
            case "autoregenbypass":
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + "Can only be executed by a player");
                    return true;
                }
                Player player = (Player) sender;
                testBypass(player);
                boolean bypassEnabled = !bypasses.get(player.getUniqueId());
                bypasses.put(player.getUniqueId(), bypassEnabled);
                sender.sendMessage(ChatColor.DARK_GREEN + "Toggled bypass " + (bypassEnabled ? "on" : "off"));
                break;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    private void onBlockBreak(final BlockBreakEvent event) {
        if(testBypass(event.getPlayer()))
            return;

        RegenGroup regenGroup = getRegenGroup(event.getBlock().getLocation());
        if(regenGroup == null)
            return;

        Block block = event.getBlock();
        PlayerInventory inventory = event.getPlayer().getInventory();
        ItemStack tool = inventory.getItemInHand();
        Collection<ItemStack> drops = block.getDrops(tool);
        final RegenContext context = new RegenContext(event.getPlayer(), block.getState(), tool, inventory, drops);

        final RegeneratorSet regeneratorSet = regenGroup.getRegenerator(context);
        if(regeneratorSet == null) {
            if(regenGroup.isProtected())
                event.setCancelled(true);
            return;
        }
        event.setCancelled(true);

        final Regenerator regenerator = regeneratorSet.getRegenerator();

        final Object breakdownData = regenerator.breakdown(context);

        for (ItemStack itemStack : context.getDrops()) {
            block.getWorld().dropItemNaturally(block.getLocation(), itemStack);
        }

        final long delay = regeneratorSet.getTimeRules().getTime() / (1000 / 20);
        class TaskHolder { public BukkitTask task; }
        // Bypass java anonymous reference limitation
        final TaskHolder holder = new TaskHolder();

        TimerTask runnable = new TimerTask() {
            @Override
            @SuppressWarnings("unchecked")
            public void run() {
                regenerator.regenerate(context, breakdownData);
                regenTasks.remove(holder.task);
            }
        };
        holder.task = Bukkit.getScheduler().runTaskLater(this, runnable, delay);
        regenTasks.put(holder.task, runnable);
    }


    @EventHandler
    private void onPlayerQuit(PlayerQuitEvent event) {
        bypasses.remove(event.getPlayer().getUniqueId());
    }

    private boolean testBypass(Player player) {
        if(!bypasses.containsKey(player.getUniqueId())) {
            boolean value = player.hasPermission("autoregen.bypass");
            bypasses.put(player.getUniqueId(), value);
            return value;
        }
        return bypasses.get(player.getUniqueId());
    }

    WGCustomFlagsPlugin setupCustomFlags() {
        Plugin plugin = getServer().getPluginManager().getPlugin("WGCustomFlags");
        return plugin instanceof WGCustomFlagsPlugin ? (WGCustomFlagsPlugin) plugin : null;
    }

    private RegenGroup getRegenGroup(Location location) {
        ApplicableRegionSet applicableRegions = worldGuard.getRegionManager(location.getWorld()).getApplicableRegions(location);
        String flag = applicableRegions.getFlag(AUTOREGEN);
        if(flag == null)
            return null;

        RegenGroup regenGroup = regenGroups.get(flag);

        if(regenGroup == null) {
            StringBuilder sb = new StringBuilder();
            for (ProtectedRegion applicableRegion : applicableRegions) {
                sb.append(applicableRegion.getId()).append(" ");
            }
            sb.setLength(sb.length() - 1);
            getLogger().severe(String.format("One of the regions \"%s\" contains unknown value \"%s\" for flag \"%s\"", sb, flag, AUTOREGEN.getName()));
        }
        return regenGroup;
    }

    public static AutoRegenPlugin instance() {
        return instance;
    }

    public Toaster getToaster() {
        return toaster;
    }
}
