package me.realized.tokenmanager.data.database;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import me.realized.tokenmanager.TokenManagerPlugin;
import me.realized.tokenmanager.util.Log;
import me.realized.tokenmanager.util.profile.ProfileUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public class FileDatabase extends AbstractDatabase {

    private static final String SERVER_MODE_MISMATCH = "Some player balances were skipped while loading: Server is in %s mode, but given keys are not a valid %s!";

    private final File file;
    private final Map<String, Long> data = new HashMap<>();

    public FileDatabase(final TokenManagerPlugin plugin) throws IOException {
        super(plugin);
        this.file = new File(plugin.getDataFolder(), "data.yml");

        if (!file.exists()) {
            file.createNewFile();
        }
    }

    @Override
    public void setup() {
        final FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        final ConfigurationSection section = config.getConfigurationSection("Players");

        if (section != null) {
            boolean warned = false;

            for (final String key : section.getKeys(false)) {
                if (!warned && ProfileUtil.isUUID(key) != online) {
                    Log.error(String.format(SERVER_MODE_MISMATCH, online ? "online" : "offline", !online ? "UUID" : "NAME"));
                    warned = true;
                    continue;
                }

                data.put(key, section.getLong(key));
            }
        }
    }

    @Override
    public OptionalLong get(final Player player) {
        return from(data.get(online ? player.getUniqueId().toString() : player.getName()));
    }

    @Override
    public void get(final Player player, final Consumer<OptionalLong> consumer, final Consumer<String> errorHandler) {
        consumer.accept(get(player));
    }

    @Override
    public void get(final String key, final Consumer<OptionalLong> consumer, final Consumer<String> errorHandler, final boolean create) {
        final OptionalLong cached = from(data.get(key));

        if (!cached.isPresent()) {
            final long defaultBalance = plugin.getConfiguration().getDefaultBalance();
            data.put(key, defaultBalance);
            consumer.accept(OptionalLong.of(defaultBalance));
            return;
        }

        consumer.accept(cached);
    }

    @Override
    public void set(final Player player, final long value) {
        data.put(online ? player.getUniqueId().toString() : player.getName(), value);
    }

    @Override
    public void set(final String key, final boolean set, final long amount, final long updated, final Runnable action, final Consumer<String> errorHandler) {
        plugin.doSync(() -> {
            if (set) {
                data.put(key, amount);
                return;
            }

            final OptionalLong cached = from(data.get(key));

            if (!cached.isPresent()) {
                return;
            }

            data.put(key, cached.getAsLong() + amount);

            final Player player;

            if (ProfileUtil.isUUID(key)) {
                player = Bukkit.getPlayer(UUID.fromString(key));
            } else {
                player = Bukkit.getPlayerExact(key);
            }

            if (player == null) {
                return;
            }

            if (amount > 0) {
                plugin.getLang().sendMessage(player, true, "COMMAND.receive", "amount", amount);
            } else {
                plugin.getLang().sendMessage(player, true, "COMMAND.take", "amount", Math.abs(amount));
            }
        });
        action.run();
    }

    @Override
    public void save(final Player player) {}

    // TODO: 3/8/18 Safe n stuff
    @Override
    public void save() {}

    @Override
    public void ordered(final int limit, final Consumer<List<TopElement>> consumer) {
        final List<TopElement> elements = new ArrayList<>();

        if (limit <= 0 || data.isEmpty()) {
            consumer.accept(elements);
            return;
        }

        data.forEach((key, value) -> elements.add(new TopElement(key, value)));

        plugin.doAsync(() -> {
            elements.sort(Comparator.comparingLong(TopElement::getTokens).reversed());
            final List<TopElement> result = elements.size() > limit ? elements.subList(0, 10) : elements;
            checkNames(result.stream().map(element -> UUID.fromString(element.getKey())).collect(Collectors.toList()), result, consumer);
        });
    }
}
