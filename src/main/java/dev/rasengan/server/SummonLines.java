package dev.rasengan.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.rasengan.Rasengan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The dragon's awakening lines, loaded from data files.
 *
 * <h2>Why a datapack resource rather than a config entry</h2>
 * A long list of prose does not belong in a TOML config: it would be unreadable and awkward to
 * quote. Loading from {@code data/<namespace>/summon_lines/*.json} means a server owner can edit the
 * shipped file, override it from a datapack, or write their own and point {@code lines_resource} at
 * it, and {@code /reload} applies the change with no restart.
 *
 * <h2>Which file actually speaks</h2>
 * Every file under the directory is loaded, but exactly one is spoken from: the one named by the
 * {@code lines_resource} config value. Loading all of them and then choosing is what makes that
 * config option meaningful - pooling everything unconditionally would silently ignore it. So there
 * are three supported ways to change the dragon's dialogue, and adding an unrelated extra file is
 * deliberately <em>not</em> one of them:
 * <ul>
 *   <li>edit the shipped {@code rasengan:awakening} file;</li>
 *   <li>override that same path from a datapack (datapacks replace by path, they do not merge);</li>
 *   <li>ship a new file and set {@code lines_resource} to it.</li>
 * </ul>
 * Pooling every loaded file is the <em>fallback</em> only, for when the configured file is missing or
 * empty - a typo in the config should not silence the dragon.
 *
 * <h2>Selection is server-side</h2>
 * The chosen line is picked here and sent as finished text. If each client picked its own, two players
 * watching the same summon would read different dialogue - which is exactly the desync the rest of
 * this mod is built to avoid.
 */
public final class SummonLines extends SimpleJsonResourceReloadListener<JsonElement> {

    private static final Logger LOGGER = LoggerFactory.getLogger("rasengan/SummonLines");
    private static final String DIRECTORY = "summon_lines";

    /**
     * Used only if no data file loads at all - a broken datapack should not silence the dragon.
     * The real set lives in data/rasengan/summon_lines/awakening.json.
     */
    private static final List<String> FALLBACK = List.of(
            "Summoned again. Thrilling.",
            "Fine. I'm here. Try not to waste my time.",
            "Wake me for a real threat next time.");

    private static final SummonLines INSTANCE = new SummonLines();

    /** namespace:path (without the directory or extension) -> lines from that file. */
    private final Map<Identifier, List<String>> byFile = new java.util.HashMap<>();

    private SummonLines() {
        // Codec + FileToIdConverter is the 26.1 constructor shape. ExtraCodecs.JSON keeps the raw
        // element so this class can report a clear warning per bad file instead of a codec error
        // aborting the whole reload.
        super(net.minecraft.util.ExtraCodecs.JSON,
                net.minecraft.resources.FileToIdConverter.json(DIRECTORY));
    }

    public static SummonLines get() {
        return INSTANCE;
    }

    @Override
    protected void apply(Map<Identifier, JsonElement> entries, ResourceManager manager,
                         ProfilerFiller profiler) {
        byFile.clear();
        int total = 0;
        for (Map.Entry<Identifier, JsonElement> entry : entries.entrySet()) {
            List<String> lines = parse(entry.getKey(), entry.getValue());
            if (!lines.isEmpty()) {
                byFile.put(entry.getKey(), lines);
                total += lines.size();
            }
        }
        LOGGER.info("Loaded {} dragon awakening line(s) from {} file(s)", total, byFile.size());
        if (byFile.isEmpty()) {
            LOGGER.warn("No awakening lines loaded; falling back to {} built-in line(s)", FALLBACK.size());
        }
    }

    private static List<String> parse(Identifier id, JsonElement element) {
        List<String> out = new ArrayList<>();
        if (!element.isJsonObject()) {
            LOGGER.warn("Ignoring {}: expected a JSON object with a 'lines' array", id);
            return out;
        }
        JsonObject object = element.getAsJsonObject();
        if (!object.has("lines") || !object.get("lines").isJsonArray()) {
            LOGGER.warn("Ignoring {}: missing a 'lines' array", id);
            return out;
        }
        JsonArray array = object.getAsJsonArray("lines");
        for (JsonElement item : array) {
            if (!item.isJsonPrimitive()) {
                continue;
            }
            String line = item.getAsString().strip();
            // Blank entries would render as an empty chat message, which reads as a bug.
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * The set of lines that will actually be spoken.
     *
     * @param preferred the configured resource; if it has lines, only those are used, otherwise
     *                  every loaded file is pooled so a bad config value still speaks
     */
    private List<String> pool(Identifier preferred) {
        List<String> chosen = byFile.get(preferred);
        if (chosen != null && !chosen.isEmpty()) {
            return chosen;
        }
        List<String> pooled = new ArrayList<>();
        for (List<String> lines : byFile.values()) {
            pooled.addAll(lines);
        }
        return pooled.isEmpty() ? FALLBACK : pooled;
    }

    /** Picks one line at random from whichever set {@link #pool} resolves to. */
    public String pick(RandomSource random, Identifier preferred) {
        List<String> pool = pool(preferred);
        return pool.get(random.nextInt(pool.size()));
    }

    /**
     * How many lines the dragon can actually say right now.
     *
     * <p>Deliberately not the total across every loaded file: an extra file that
     * {@code lines_resource} does not point at contributes nothing to what gets spoken, so counting
     * it here would report a number the dragon cannot reach.
     */
    public int poolSize(Identifier preferred) {
        return pool(preferred).size();
    }

    /** Total lines across every loaded file, whether or not they are the ones being spoken. */
    public int loadedTotal() {
        int n = 0;
        for (List<String> lines : byFile.values()) {
            n += lines.size();
        }
        return n;
    }

    public List<Identifier> files() {
        return Collections.unmodifiableList(new ArrayList<>(byFile.keySet()));
    }

    /** Parses the configured {@code namespace:path} value, falling back to the shipped file. */
    public static Identifier configuredResource(String raw) {
        Identifier id = Identifier.tryParse(raw);
        return id != null ? id : Rasengan.id("awakening");
    }
}
