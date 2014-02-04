package io.github.basicmark.config;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.entity.Player;

public class PlayerStateLoader2 extends ConfigurationSerialization {
	private String prefix;
	private File configDir;
	
	public PlayerStateLoader2(String prefix, String configPath) {
		super(null);
		this.configDir = new File(configPath);
		this.prefix = prefix;
	}

	protected String getConfigPath(Player player) {
		return configDir + "/" + player.getName() + ".yml";
	}
	
	protected File getConfigFile(Player player) {
		return new File(getConfigPath(player));
	}
	
	protected YamlConfiguration getConfig(Player player) throws FileNotFoundException, IOException, InvalidConfigurationException {
		YamlConfiguration result = new YamlConfiguration();
		result.load(getConfigFile(player));
		return result;
	}
	
	protected void saveConfig(Player player, PlayerState state) throws IOException {
		YamlConfiguration result = new YamlConfiguration();
		result.set("player", state);
		result.save(getConfigFile(player));
	}
	
	public PlayerState load(Player player) {
		try {
			Bukkit.getLogger().info("Loading player data for " + player.getName());
			return (PlayerState) getConfig(player).get("player");
		} catch(FileNotFoundException e) {
			return null;
		} catch(Exception e) {
			Bukkit.getLogger().log(Level.SEVERE, prefix + ": Failed to load player data for " + player.getName(), e);
			return null;
		}
	}
	
	public void save(Player player, PlayerState state) {
		if(player == null) {
			return;
		}
		try {
			saveConfig(player, state);
		} catch(IOException e) {
			Bukkit.getLogger().log(Level.SEVERE, prefix + ": Failed to save player data for " + player.getName(), e);
			return;
		}
	}

	public boolean exists(Player player) {
		return getConfigFile(player).exists();
	}
	
	public void delete(Player player) {
		getConfigFile(player).delete();
	}
}
