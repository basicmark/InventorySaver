package io.github.basicmark.inventorysaver;


import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.basicmark.config.PlayerState;
import io.github.basicmark.config.PlayerStateLoader2;
import io.github.basicmark.util.command.CommandProcessor;
import io.github.basicmark.util.command.CommandRunner;

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class InventorySaver extends JavaPlugin implements Listener {
	static {
		ConfigurationSerialization.registerClass(PlayerState.class);
	}

	private CommandProcessor cmdProc;
	private HashMap<String, PlayerStateLoader2> playerLoader;
	private HashMap<String, String> worldGroups;
	private Set<String> blacklistedWorlds;
	private HashMap<String, String> lastSlot;
    private File playersWorldFile = null;
    private FileConfiguration playersWorld = null;
	private static final String prefix = "[" + ChatColor.GREEN + "InventorySaver" + ChatColor.RESET + "]";

	public void onEnable(){
		getLogger().info("Enableing InventorySaver");
		
		lastSlot = new HashMap<String, String>();
		
		// Register our event handlers
		getServer().getPluginManager().registerEvents(this, this);

		// Create/load the config file
		saveDefaultConfig();
		loadConfig();

		// Create the last valid world list
		loadLastPlayerWorld();		

		cmdProc = new CommandProcessor("InventorySaver");

		cmdProc.add("save",
					new ISSave(),
					1,
					2,
					"<slot> [Player name] :- Save a players inventory",
					false,
					"inventorysaver.manual");
		cmdProc.add("restore",
					new ISRestore(),
					1,
					2,
					"<slot> [Player name] :- Restore a players inventory",
					false,
					"inventorysaver.manual");
		cmdProc.add("slot-add",
					new ISSlotAdd(),
					1,
					"<slot name> :- Add a save slot",
					false,
					"inventorysaver.admin");
		cmdProc.add("slot-remove",
					new ISSlotRemove(),
					1,
					"<slot name> :- Remove a save slot",
					false,
					"inventorysaver.admin");
		cmdProc.add("blacklist-add",
					new ISBlacklistAdd(),
					1,
					"<world name> :- Add a world to the blacklist",
					false,
					"inventorysaver.admin");
		cmdProc.add("blacklist-remove",
					new ISBlacklistRemove(),
					1,
					"<world name> :- Remove a world from the blacklist",
					false,
					"inventorysaver.admin");
		cmdProc.add("settings",
					new ISSettings(),
					0,
					" :- Display the current settings",
					false,
					"inventorysaver.admin");
		cmdProc.add("reload",
					new ISReload(),
					0,
					" :- Reload the config file",
					false,
					"inventorysaver.admin");
	}
 
	public void onDisable(){
		getLogger().info("Disabling InventorySaver");
	}

    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
    	if (cmd.getName().equalsIgnoreCase("InventorySaver")){
    		return cmdProc.run(sender, args);
    	}
    	return false;
    }
 
    @EventHandler
    public void onPlayerJoinEvent(PlayerJoinEvent event) {
    	Player player = event.getPlayer();

    	if (player.hasPermission("inventorysaver.changeworld")) {
    		if (!lastSlot.containsKey(player.getName())) {
    			String playerName = player.getName();
    			String worldSlot = getWorldSlotName(player.getWorld());
    	   	
    			if (worldSlot != null) {
    				lastSlot.put(playerName, worldSlot);
    				saveLastPlayerWorldSlot(player, worldSlot);
    			}
    	   	}
    	}
    }

    @EventHandler
    public void onPlayerChangedWorldEvent(PlayerChangedWorldEvent event) {
    	Player player = event.getPlayer();
    	
    	/*
    	 * Work out if we need to save/restore the players inventory applying the following rules:
    	 * 
    	 * 1) If the player has moved to a a world in the same group as the previous one
    	 *    then don't save/restore.
    	 * 2) If a player transitions through a blacklisted world then don't save/restore
    	 *    in the blacklisted world but rather treat it as if the player never entered said
    	 *    world and save the inventory against the last valid world.
    	 *    For example if foobar is an blacklisted world and the player does:
    	 *    	world -> foobar -> world
    	 *    Then no inventory save/restore will happen.
    	 *    If a player does:
    	 *    	world -> foobar -> sandbox
    	 *    Then (assuming world and sandbox are not in the same group) when the player moves from
    	 *    foobar to sandbox their current inventory will be saved against world and restored
    	 *    from sandbox.
    	 */
    	if (player.hasPermission("inventorysaver.changeworld")) {
    		// If the world isn't in a group just use its name
    		Boolean needSave = false;
        	String saveSlot;
        	String loadSlot;

        	saveSlot = lastSlot.get(player.getName());
        	/*
        	 * If a players last world slot was null but the world they came from
        	 * was a valid (non-blacklisted) world then it means they've just got
        	 * parms and this is their first world change
        	 */
        	if ((saveSlot == null) && (getWorldSlotName(event.getFrom())) != null) {
        		saveSlot = getWorldSlotName(event.getFrom());
        	}

        	loadSlot = getWorldSlotName(player.getWorld());
        	if (loadSlot != null) {
        		lastSlot.put(player.getName(), loadSlot);
        	}
    		
    		if (saveRequired(saveSlot, loadSlot)) {
    			// Save the players inventory for the previous world/world group
    			PlayerStateLoader2 saver = playerLoader.get(saveSlot);
    			// Create the world slots on demand
    			if (saver == null) {
    				saver = new PlayerStateLoader2(prefix, getDataFolder() + "/inventories/" + saveSlot);
    				playerLoader.put(saveSlot, saver);
    				needSave = true;
    			}
    			PlayerState playerState = new PlayerState(player, true, false, false);
    			saver.save(player, playerState);

    			// Load the players inventory for the new world/world group
    			PlayerStateLoader2 loader = playerLoader.get(loadSlot);
    			// Create the world slots on demand
    			if (loader == null) {
    				loader = new PlayerStateLoader2(prefix, getDataFolder() + "/inventories/" + loadSlot);
    				playerLoader.put(loadSlot, loader);
    				needSave = true;
    			}
    			// This could be the players first time in this world, if so there is nothing to load
    			if (loader.exists(player)) {
    				PlayerState state = loader.load(player);
    				state.restore(player);
    				loader.delete(player);
    			}

        		if (needSave) {
        			saveConfig();
        		}
        		
        		saveLastPlayerWorldSlot(player, loadSlot);
    		}
    	}
    }

    private String getWorldSlotName(World world) {
    	String slotName = null;
    	
    	// First check if the world is blacklisted
    	if (!blacklistedWorlds.contains(world.getName())) {
    		slotName = world.getName();
    	} else {
    		return null;
    	}
    	
    	// Then check if this world is in a group
    	if (worldGroups.containsKey(slotName)) {
    		slotName = worldGroups.get(slotName);
    	}
    	return slotName;
    }
    
    public boolean saveRequired(String saveSlot, String loadSlot) {
    	if ((saveSlot == null) || (loadSlot == null)) {
    		return false;
    	} else {
    		boolean save = !saveSlot.equals(loadSlot);
    		return save;
    	}
    }
    
    public void loadConfig() {
    	FileConfiguration config = getConfig();
    	
    	/* Create new objects on each load as we don't want the values from a previous reload */
		playerLoader = new HashMap<String, PlayerStateLoader2>();
		worldGroups = new HashMap<String, String>();
		blacklistedWorlds = new HashSet<String>();

    	List<String> slots = config.getStringList("slots");
    	Iterator<String> is = slots.iterator();
    	while(is.hasNext()) {
    		String slotName =  is.next();
    		if (playerLoader.containsKey(slotName)) {
    			this.getServer().getLogger().warning(prefix + ": Config file has " + slotName + " listed multiple times in the slot list");
    		} else {
    			playerLoader.put(slotName, new PlayerStateLoader2(prefix, getDataFolder()+"/inventories/"+slotName));
    		}
    	}
    	
    	Set<String> groupNames = config.getConfigurationSection("worldgroups").getKeys(true);
    	if (groupNames != null) {
    		Iterator<String> ig = groupNames.iterator();
    		while(ig.hasNext()) {
    			String group = ig.next();
    			List<String> worlds = config.getStringList("worldgroups." + group);

    			Iterator<String> in = worlds.iterator();
    			while(in.hasNext()) {
    				String world = in.next();
    				worldGroups.put(world, group);
    			}
    		}
    	}

    	List<String> blworlds = config.getStringList("blacklistedworlds");
    	Iterator<String> ibl = blworlds.iterator();
    	while(ibl.hasNext()) {
    		String name =  ibl.next();
    		if (blacklistedWorlds.contains(name)) {
    			this.getServer().getLogger().warning(prefix + ": Config file has " + name + " listed multiple times in the blacklist");
    		} else {
    			blacklistedWorlds.add(name);
    		}
    	}
    }
    
    public void saveConfig() {
    	FileConfiguration config = getConfig();
    	List<String> slotList = new ArrayList<String>(playerLoader.keySet());
    	config.set("slots", slotList);

    	/* 
    	 * Saving the world group data is a bit tricky as we create a world -> group hash
    	 * in the dynamic data the plug-in uses as that is the way we need to do the
    	 * translation but the config file has a list of groups which contain a list of worlds
    	 * that belong to said group.
    	 * 
    	 * This means we need to flip the world -> group hash into a group -> world(s) hash
    	 * and then save this data. This is done as a two stage process, first walk all the
    	 * worlds to create the group hash and then walk the created hash and store the
    	 * data. It can't be done in the same stage as the world -> group hash has no order
    	 * and the group world lists need to be complete before they can be added to the config.
    	 */
		Iterator<Map.Entry<String,String>> ig = worldGroups.entrySet().iterator();
		HashMap<String,List<String>> worldGroupHash = new HashMap<String,List<String>>();
		while (ig.hasNext()) {
			Map.Entry<String,String> ent = ig.next();
			List<String> worldGroupList = worldGroupHash.get(ent.getValue());
			if (worldGroupList == null) {
				worldGroupList = new ArrayList<String>();
				worldGroupHash.put(ent.getValue(), worldGroupList);
			}
			worldGroupList.add(ent.getKey());
		}
		
		Iterator<Map.Entry<String, List<String>>> iwgh = worldGroupHash.entrySet().iterator();
		while (iwgh.hasNext()) {
			Map.Entry<String, List<String>> ent = iwgh.next();
			config.set("worldgroups." + ent.getKey(), ent.getValue());
		}

    	List<String> blacklistList = new ArrayList<String>(blacklistedWorlds);
    	config.set("blacklistedworlds", blacklistList);
		
    	// Save the changes to the file
		super.saveConfig();
    }
    
    private void loadLastPlayerWorld() {
    	// Load the players last known world
		playersWorldFile = new File(getDataFolder(), "playersworld.yml");
		playersWorld = YamlConfiguration.loadConfiguration(playersWorldFile);
    	ConfigurationSection section = playersWorld.getConfigurationSection("worldslot");
    	
        if (section == null) {
        	// No worldslot history section so this must be the first time this plug-in has been run.
        	// Create the section and add any players that are already on-line into it
        	playersWorld.createSection("worldslot");
        	
        	Player[] onlinePlayerList = getServer().getOnlinePlayers();
        	for(Player player : onlinePlayerList) {
        		if (player.hasPermission("inventorysaver.changeworld")) {
        			String currentSlot = getWorldSlotName(player.getWorld());
        			if (currentSlot != null) {
        				playersWorld.set("worldslot." + player.getName(), currentSlot);
        			}
        		}
        	}

        	try {
        		playersWorld.save(playersWorldFile);
        	} catch (IOException ex) {
        		getLogger().severe("Could not save config to " + playersWorldFile);
        	}
        	section = playersWorld.getConfigurationSection("worldslot");
        }
	
        Set<String> playerSet = section.getKeys(true);
        if (playerSet != null) {
        	Iterator<String> i = playerSet.iterator();

        	while(i.hasNext()) {
        		String player = i.next();
        		String playerlastworld = playersWorld.getString("worldslot." + player);

        		lastSlot.put(player, playerlastworld);
        	}
		}
    }
    
    private void saveLastPlayerWorldSlot(Player player, String worldSlot) {
    	if (worldSlot != null) {
    		playersWorld.set("worldslot." + player.getName(), worldSlot);

        	try {
        		playersWorld.save(playersWorldFile);
        	} catch (IOException ex) {
        		getLogger().severe("Could not save config to " + playersWorldFile);
        	}
    	}
    }

    private class ISSave implements CommandRunner {
    	public boolean run(CommandSender sender, String args[]) {
    		Player player;
    		String slotName = args[0];

    		if (args.length == 2) {
    			player = getServer().getPlayer(args[1]);
    			if (player == null) {
    				sender.sendMessage(prefix + ": Failed to find player by name");
    				return true;
    			}
    		} else if (sender instanceof Player) {
    			player = (Player) sender;
    		} else {
    			sender.sendMessage(prefix + ": Asked to load from console without player name");
    			return true;
    		}    		

    		if (!playerLoader.containsKey(slotName)) {
    			sender.sendMessage(prefix + ": Unknown save slot");
    			return true;
    		}
    		
    		PlayerStateLoader2 loader = playerLoader.get(slotName);
    		
    		if (loader.exists(player)) {
    			sender.sendMessage(prefix + ": Resfusing to save inventory as it would overwrite an existing save");
    			return true;
    		}

    		PlayerState playerState = new PlayerState(player, true, false, false);
    		loader.save(player, playerState);
    		
    		return true;
    	}
    }

    private class ISRestore implements CommandRunner {
    	public boolean run(CommandSender sender, String args[]) {
    		Player player;
    		String slotName = args[0];

    		if (args.length == 2) {
    			player = getServer().getPlayer(args[1]);
    			if (player == null) {
    				sender.sendMessage(prefix + ": Failed to find player by name");
    				return true;
    			}
    		} else if (sender instanceof Player) {
    			player = (Player) sender;
    		} else {
    			sender.sendMessage(prefix + ": Asked to restore from console without player name");
    			return true;
    		}
    		
    		if (!playerLoader.containsKey(slotName)) {
    			sender.sendMessage(prefix + ": Unknown save slot");
    			return true;
    		}
    		
    		PlayerStateLoader2 loader = playerLoader.get(slotName);
    		PlayerState state = loader.load(player);
    		state.restore(player);
    		loader.delete(player);
    		
    		return true;
    	}
    }

    private class ISSlotAdd implements CommandRunner {
    	public boolean run(CommandSender sender, String args[]) {
    		String slotName = args[0];
    		if (playerLoader.containsKey(playerLoader)) {
    			sender.sendMessage(prefix + ChatColor.RED + ": Slot " + slotName + " already exists");
    			return true;
    		}
    		playerLoader.put(slotName, new PlayerStateLoader2(prefix, getDataFolder()+"/inventories/"+slotName));
    		saveConfig();
    		sender.sendMessage(prefix + ChatColor.GREEN + ": Slot " + slotName + " added");
    		return true;
    	}
    }
    
    private class ISSlotRemove implements CommandRunner {
    	public boolean run(CommandSender sender, String args[]) {
    		String slotName = args[0];
    		PlayerStateLoader2 loader = playerLoader.get(slotName);
    		if (loader == null) {
    			sender.sendMessage(prefix + ChatColor.RED + ": Slot " + slotName + " doesn't exist");
    			return true;
    		}

    		// We don't delete the config files for this slot in case they are wanted
    		playerLoader.remove(slotName);
    		saveConfig();
    		sender.sendMessage(prefix + ChatColor.GREEN+ ": Slot " + slotName + " removed");
    		return true;
    	}
    }
 
    private class ISBlacklistAdd implements CommandRunner {
    	public boolean run(CommandSender sender, String args[]) {
    		String addWorld = args[0];
    		
    		if (blacklistedWorlds.contains(addWorld)) {
    			sender.sendMessage(prefix + ChatColor.RED + ": World " + addWorld + " already in blacklist");
    			return true;
    		}
    		blacklistedWorlds.add(addWorld);
    		saveConfig();
    		sender.sendMessage(prefix + ChatColor.GREEN+ ": World " + addWorld + " added to blacklist");
    		return true;
    	}
    }
    
    private class ISBlacklistRemove implements CommandRunner {
    	public boolean run(CommandSender sender, String args[]) {
    		String removeWorld = args[0];

    		if (!blacklistedWorlds.contains(removeWorld)) {
    			sender.sendMessage(prefix + ChatColor.RED + ": World " + removeWorld + " is not in the blacklist");
    			return true;
    		}
    		blacklistedWorlds.remove(removeWorld);
    		saveConfig();
    		sender.sendMessage(prefix + ChatColor.GREEN+ ": World " + removeWorld + " removed from blacklist");
    		return true;
    	}
    }

    private class ISSettings implements CommandRunner {
    	public boolean run(CommandSender sender, String args[]) {
    		sender.sendMessage(ChatColor.GOLD + "Settings for InventorySaver:");
    		sender.sendMessage("slots:");
        	Iterator<String> is = playerLoader.keySet().iterator();
        	while (is.hasNext()) {
        		String name = is.next();
        		
        		sender.sendMessage(ChatColor.GRAY + " " + name);
        	}

        	sender.sendMessage("worldgroups:");
    		Iterator<Map.Entry<String,String>> ig = worldGroups.entrySet().iterator();
    		while (ig.hasNext()) {
    			Map.Entry<String,String> ent = ig.next();

    			sender.sendMessage(ChatColor.GRAY + " " + ent.getKey() + " belongs to group " + ent.getValue());
    		}

    		sender.sendMessage("blacklistedworlds:");
    	   	Iterator<String> ib = blacklistedWorlds.iterator();
        	while (ib.hasNext()) {
        		String name = ib.next();
        		
        		sender.sendMessage(ChatColor.GRAY + " " + name);
        	}
    		return true;
    	}
    }
    
    private class ISReload implements CommandRunner {
    	public boolean run(CommandSender sender, String args[]) {
    		reloadConfig();
    		loadConfig();
    		return true;
    	}
    }
}

