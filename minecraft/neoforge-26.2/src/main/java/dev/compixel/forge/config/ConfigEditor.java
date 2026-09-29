package dev.compixel.forge.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.mojang.logging.LogUtils;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client game-thread config editing through loader APIs. Drafts never mutate running config values. */
public final class ConfigEditor {
    public enum Kind {
        BOOLEAN,
        INTEGER,
        LONG,
        DECIMAL,
        STRING,
        ENUM,
        LIST,
        UNSUPPORTED
    }

    public enum Access {
        EDITABLE,
        NOT_LOADED,
        REMOTE_SERVER,
        LAN_SERVER,
        RELOADED,
        UNSUPPORTED
    }

    public enum Result {
        OK,
        INVALID,
        READ_ONLY,
        CONFLICT,
        SAVE_FAILED,
        UNKNOWN
    }

    public record Input(String text, List<String> elements) {
        public Input {
            text = Objects.requireNonNull(text);
            if (text.length() > MAX_TEXT || elements.size() > MAX_LIST)
                throw new IllegalArgumentException("Config input too large");
            elements = List.copyOf(elements);
        }

        public static Input scalar(String value) {
            return new Input(value, List.of());
        }

        public static Input list(List<String> values) {
            return new Input("", values);
        }
    }

    public record EntryView(
            String id,
            List<String> path,
            String translationKey,
            String comment,
            Kind kind,
            Input value,
            Input defaults,
            List<String> choices,
            String range,
            ModConfigSpec.RestartType restart,
            boolean changed,
            boolean canAdd,
            String newElement) {}

    public record FileView(
            String id,
            ModConfig.Type type,
            Access access,
            List<EntryView> entries,
            int changes,
            boolean sharedServerFile) {}

    public record SaveResult(Result result, ModConfigSpec.RestartType restart, int changes) {}

    private static final int MAX_LIST = 100_000, MAX_TEXT = 1_048_576;
    // A retained editor must not keep a stopped integrated server/world alive.
    private static final Map<Object, Object> CONTEXTS = new WeakHashMap<>();
    private final Thread owner = Thread.currentThread();
    private final Map<String, FileState> files = new LinkedHashMap<>();

    public ConfigEditor(String modId) {
        if (!Minecraft.getInstance().isSameThread())
            throw new IllegalStateException("Config editing belongs to the client game thread");
        for (var config : List.copyOf(ModConfigs.getModConfigs(modId)))
            files.put(config.getFileName(), new FileState(config));
    }

    private void checkThread() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Config editing belongs to its creating game thread");
    }

    public List<FileView> snapshot() {
        checkThread();
        for (var file : files.values()) file.rebaseUnchanged();
        return files.values().stream()
                .map(file -> new FileView(
                        file.config.getFileName(),
                        file.config.getType(),
                        access(file),
                        file.views(),
                        file.changes(),
                        file.config.getType() == ModConfig.Type.SERVER
                                && file.path != null
                                && file.path.startsWith(net.neoforged.fml.loading.FMLPaths.CONFIGDIR
                                        .get()
                                        .toAbsolutePath()
                                        .normalize())))
                .toList();
    }
    /** Validate and stage one immutable editor input. This does not save or change runtime config. */
    public Result stage(String fileId, String entryId, Input input) {
        checkThread();
        var file = files.get(fileId);
        if (file == null) return Result.UNKNOWN;
        if (access(file) != Access.EDITABLE) return Result.READ_ONLY;
        var entry = file.entries.get(entryId);
        if (entry == null) return Result.UNKNOWN;
        try {
            Object parsed = entry.parse(input);
            if (!entry.valid(parsed)) return Result.INVALID;
            file.draft(entry, Objects.equals(parsed, entry.base) ? null : copy(parsed));
            return Result.OK;
        } catch (IllegalArgumentException failure) {
            return Result.INVALID;
        }
    }

    public Result reset(String fileId, String entryId) {
        checkThread();
        var file = files.get(fileId);
        if (file == null) return Result.UNKNOWN;
        if (access(file) != Access.EDITABLE) return Result.READ_ONLY;
        var entry = file.entries.get(entryId);
        if (entry == null) return Result.UNKNOWN;
        if (entry.kind == Kind.UNSUPPORTED) return Result.READ_ONLY;
        Object value = copy(entry.value.getDefault());
        if (!entry.valid(value)) return Result.INVALID;
        file.draft(entry, Objects.equals(value, entry.base) ? null : value);
        return Result.OK;
    }
    /** Discard this file's drafts and capture its current raw values, including any external reload. */
    public Result reload(String fileId) {
        checkThread();
        var file = files.get(fileId);
        if (file == null) return Result.UNKNOWN;
        try {
            file.reload();
            return Result.OK;
        } catch (IllegalStateException reloading) {
            return Result.CONFLICT;
        }
    }

    public void discardAll() {
        checkThread();
        files.values().forEach(FileState::clearDrafts);
    }

    public int changes() {
        checkThread();
        return files.values().stream().mapToInt(FileState::changes).sum();
    }

    public SaveResult save(String fileId) {
        checkThread();
        var file = files.get(fileId);
        if (file == null) return new SaveResult(Result.UNKNOWN, ModConfigSpec.RestartType.NONE, 0);
        if (access(file) != Access.EDITABLE) return new SaveResult(Result.READ_ONLY, ModConfigSpec.RestartType.NONE, 0);
        var changed =
                file.entries.values().stream().filter(e -> e.draft != null).toList();
        if (changed.isEmpty()) return new SaveResult(Result.OK, ModConfigSpec.RestartType.NONE, 0);
        var restart = ModConfigSpec.RestartType.NONE;
        try {
            for (var entry : changed) {
                if (!entry.valid(entry.draft)) return new SaveResult(Result.INVALID, restart, 0);
                if (!Objects.equals(entry.base, entry.value.getRaw()))
                    return new SaveResult(Result.CONFLICT, restart, 0);
                restart = restart.with(
                        file.config.getType() == ModConfig.Type.STARTUP
                                ? ModConfigSpec.RestartType.GAME
                                : entry.value.getSpec().restartType());
            }
        } catch (RuntimeException reloaded) {
            return new SaveResult(Result.CONFLICT, restart, 0);
        }
        try {
            for (var entry : changed) {
                if (file.loaded != file.config.getLoadedConfig())
                    throw new IllegalStateException("Configuration reloaded while saving");
                entry.set(entry.draft);
            }
            if (file.loaded != file.config.getLoadedConfig())
                throw new IllegalStateException("Configuration reloaded before save");
            file.spec.save(); // Writes the loader-owned file and dispatches its normal Reloading event.
        } catch (RuntimeException failure) {
            // Best effort rollback of the fields this editor owns. Consumer event side effects cannot be undone
            // generically.
            try {
                if (file.loaded == file.config.getLoadedConfig()) {
                    for (var entry : changed) entry.set(entry.base);
                    file.spec.save();
                } else file.spec.afterReload();
            } catch (RuntimeException rollback) {
                failure.addSuppressed(rollback);
            }
            LogUtils.getLogger().error("Could not save config {}", fileId, failure);
            return new SaveResult(Result.SAVE_FAILED, restart, 0);
        }
        file.clearDrafts();
        try {
            file.reload();
        } catch (IllegalStateException reloading) {
            file.loaded = new Object();
        }
        return new SaveResult(Result.OK, restart, changed.size());
    }

    private Access access(FileState file) {
        file.rebaseUnchanged();
        if (file.spec == null) return Access.UNSUPPORTED;
        if (!file.spec.isLoaded() || file.config.getLoadedConfig() == null) return Access.NOT_LOADED;
        var mc = Minecraft.getInstance();
        if (file.config.getType() == ModConfig.Type.SERVER) {
            if (mc.getCurrentServer() != null && !mc.isLocalServer()) return Access.REMOTE_SERVER;
            if (mc.hasSingleplayerServer() && mc.getSingleplayerServer().isPublished()) return Access.LAN_SERVER;
        }
        try {
            file.config.getFullPath();
        } catch (IllegalStateException noFile) {
            return Access.NOT_LOADED;
        }
        if (file.loaded != file.config.getLoadedConfig() || file.context != contextOf(file.config))
            return Access.RELOADED;
        return Access.EDITABLE;
    }

    private static final class FileState {
        final ModConfig config;
        final ModConfigSpec spec;
        Object loaded;
        java.nio.file.Path path;
        Object context;
        final Map<String, Entry> entries = new LinkedHashMap<>();
        List<EntryView> cached;
        int changed;

        FileState(ModConfig config) {
            this.config = config;
            this.spec = config.getSpec() instanceof ModConfigSpec s ? s : null;
            try {
                reload();
            } catch (IllegalStateException reloading) {
                loaded = new Object();
            }
        }

        void rebaseUnchanged() {
            var current = config.getLoadedConfig();
            if (current == loaded && context == contextOf(config) || spec == null) return;
            if (current == null || !spec.isLoaded()) {
                if (changes() == 0 && current == null)
                    try {
                        reload();
                    } catch (IllegalStateException ignored) {
                    }
                return;
            }
            if (changes() > 0 && (!Objects.equals(path, pathOf(config)) || context != contextOf(config))) return;
            var pending = new LinkedHashMap<String, Object>();
            try {
                for (var entry : entries.values())
                    if (entry.draft != null) {
                        if (!Objects.equals(entry.base, entry.value.getRaw())) return;
                        pending.put(entry.id, entry.draft);
                    }
                if (current != config.getLoadedConfig()) return;
                reload();
                for (var entry : pending.entrySet()) {
                    var target = entries.get(entry.getKey());
                    if (target != null)
                        draft(target, Objects.equals(target.base, entry.getValue()) ? null : entry.getValue());
                }
            } catch (RuntimeException reloading) {
                /* The next snapshot will retry after the loader finishes. */
            }
        }

        void reload() {
            Object nextLoaded = config.getLoadedConfig();
            Object nextContext = contextOf(config);
            if (nextLoaded != null && spec != null && !spec.isLoaded())
                throw new IllegalStateException("Config is loading");
            var next = new LinkedHashMap<String, Entry>();
            if (spec != null) collect(spec.getValues(), next, nextLoaded != null);
            if (nextLoaded != config.getLoadedConfig() || nextContext != contextOf(config))
                throw new IllegalStateException("Config changed during capture");
            loaded = nextLoaded;
            path = pathOf(config);
            context = nextContext;
            entries.clear();
            entries.putAll(next);
            cached = null;
            changed = 0;
        }

        List<EntryView> views() {
            if (cached == null)
                cached = entries.values().stream().map(Entry::view).toList();
            return cached;
        }

        void collect(UnmodifiableConfig values, Map<String, Entry> target, boolean readLoaded) {
            for (var object : values.valueMap().values()) {
                if (object instanceof ModConfigSpec.ConfigValue<?> value) {
                    var entry = new Entry(value, readLoaded);
                    target.put(entry.id, entry);
                } else if (object instanceof UnmodifiableConfig nested) collect(nested, target, readLoaded);
            }
        }

        void draft(Entry entry, Object next) {
            changed += (next == null ? 0 : 1) - (entry.draft == null ? 0 : 1);
            entry.draft = next;
            entry.cached = null;
            cached = null;
        }

        void clearDrafts() {
            for (var entry : entries.values())
                if (entry.draft != null) {
                    entry.draft = null;
                    entry.cached = null;
                }
            changed = 0;
            cached = null;
        }

        int changes() {
            return changed;
        }
    }

    private static java.nio.file.Path pathOf(ModConfig config) {
        try {
            return config.getFullPath().toAbsolutePath().normalize();
        } catch (IllegalStateException noFile) {
            return null;
        }
    }

    private static Object contextOf(ModConfig config) {
        if (config.getType() != ModConfig.Type.SERVER) return null;
        var server = Minecraft.getInstance().getSingleplayerServer();
        return server == null ? null : CONTEXTS.computeIfAbsent(server, ignored -> new Object());
    }

    private static final class Entry {
        final ModConfigSpec.ConfigValue<?> value;
        final String id;
        final List<String> path;
        final Kind kind, elementKind;
        final Object elementSample;
        final Object base;
        Object draft;
        EntryView cached;
        final List<Object> enumValues;
        final List<String> choices;

        Entry(ModConfigSpec.ConfigValue<?> value, boolean loaded) {
            this.value = value;
            path = List.copyOf(value.getPath());
            id = path.stream()
                    .map(p -> p.replace("~", "~0").replace("/", "~1"))
                    .reduce((a, b) -> a + "/" + b)
                    .orElse("");
            base = copy(loaded ? value.getRaw() : value.getDefault());
            Kind detected = kind(base);
            if (base instanceof String text && text.length() > MAX_TEXT) detected = Kind.UNSUPPORTED;
            Object sample = null;
            if (base instanceof List<?> list) {
                if (list.size() > MAX_LIST) detected = Kind.UNSUPPORTED;
                else if (value.getSpec() instanceof ModConfigSpec.ListValueSpec listSpec
                        && listSpec.getNewElementSupplier() != null)
                    sample = listSpec.getNewElementSupplier().get();
                else if (!list.isEmpty()) sample = list.getFirst();
            }
            elementSample = sample;
            elementKind = kind(sample);
            if (detected == Kind.LIST && (elementKind == Kind.UNSUPPORTED || elementKind == Kind.LIST))
                detected = Kind.UNSUPPORTED;
            if (detected == Kind.LIST && !(value.getSpec() instanceof ModConfigSpec.ListValueSpec))
                detected = Kind.UNSUPPORTED;
            if (detected == Kind.LIST && base instanceof List<?> list)
                for (Object item : list) if (kind(item) != elementKind) detected = Kind.UNSUPPORTED;
            kind = detected;
            Object enumeration =
                    kind == Kind.ENUM ? base : kind == Kind.LIST && elementKind == Kind.ENUM ? sample : null;
            enumValues = enumeration instanceof Enum<?> e
                    ? Arrays.stream(e.getDeclaringClass().getEnumConstants())
                            .filter(v -> kind == Kind.ENUM
                                    ? value.getSpec().test(v)
                                    : ((ModConfigSpec.ListValueSpec) value.getSpec()).testElement(v))
                            .map(v -> (Object) v)
                            .toList()
                    : List.of();
            choices = enumValues.stream().map(v -> ((Enum<?>) v).name()).toList();
        }

        EntryView view() {
            if (cached != null) return cached;
            var spec = value.getSpec();
            boolean add =
                    spec instanceof ModConfigSpec.ListValueSpec listSpec && listSpec.getNewElementSupplier() != null;
            Object next = add
                    ? ((ModConfigSpec.ListValueSpec) spec)
                            .getNewElementSupplier()
                            .get()
                    : null;
            String range = spec.getRange() == null
                    ? ""
                    : spec.getRange().getMin() + " … " + spec.getRange().getMax();
            if (spec instanceof ModConfigSpec.ListValueSpec listSpec)
                range = listSpec.getSizeRange().getMin() + " … "
                        + listSpec.getSizeRange().getMax();
            return cached = new EntryView(
                    id,
                    path,
                    spec.getTranslationKey(),
                    spec.getComment() == null ? "" : spec.getComment(),
                    kind,
                    kind == Kind.UNSUPPORTED
                            ? Input.scalar(
                                    base == null ? "null" : base.getClass().getSimpleName())
                            : input(draft == null ? base : draft),
                    kind == Kind.UNSUPPORTED ? Input.scalar("") : input(value.getDefault()),
                    choices,
                    range,
                    spec.restartType(),
                    draft != null,
                    add,
                    next == null ? "" : format(next));
        }

        Object parse(Input input) {
            if (kind == Kind.UNSUPPORTED) throw new IllegalArgumentException("Unsupported config type");
            if (kind == Kind.LIST) {
                if (input.elements().size() > MAX_LIST) throw new IllegalArgumentException("Too many values");
                return input.elements().stream()
                        .map(text -> parseScalar(elementKind, text, elementSample))
                        .toList();
            }
            return parseScalar(kind, input.text(), base);
        }

        Object parseScalar(Kind type, String text, Object sample) {
            if (text.length() > MAX_TEXT) throw new IllegalArgumentException("Value too large");
            return switch (type) {
                case STRING -> text;
                case BOOLEAN -> {
                    if (!text.equals("true") && !text.equals("false"))
                        throw new IllegalArgumentException("Boolean expected");
                    yield Boolean.valueOf(text);
                }
                case INTEGER -> Integer.valueOf(text.trim());
                case LONG -> Long.valueOf(text.trim());
                case DECIMAL -> {
                    double number = Double.parseDouble(text.trim());
                    if (!Double.isFinite(number)) throw new IllegalArgumentException("Finite number expected");
                    yield number;
                }
                case ENUM ->
                    enumValues.stream()
                            .filter(v -> ((Enum<?>) v).name().equals(text))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("Enum expected"));
                default -> throw new IllegalArgumentException("Unsupported config type");
            };
        }

        boolean valid(Object candidate) {
            try {
                if (!value.getSpec().test(candidate)) return false;
                if (value.getSpec() instanceof ModConfigSpec.ListValueSpec listSpec
                        && candidate instanceof List<?> list)
                    return listSpec.getSizeRange().test(list.size())
                            && list.stream().allMatch(listSpec::testElement);
                return true;
            } catch (RuntimeException invalid) {
                return false;
            }
        }

        @SuppressWarnings("unchecked")
        void set(Object data) {
            ((ModConfigSpec.ConfigValue<Object>) value).set(data);
        }
    }

    private static Kind kind(Object value) {
        if (value instanceof Boolean) return Kind.BOOLEAN;
        if (value instanceof Integer) return Kind.INTEGER;
        if (value instanceof Long) return Kind.LONG;
        if (value instanceof Double) return Kind.DECIMAL;
        if (value instanceof String) return Kind.STRING;
        if (value instanceof Enum<?>) return Kind.ENUM;
        if (value instanceof List<?>) return Kind.LIST;
        return Kind.UNSUPPORTED;
    }

    private static Object copy(Object value) {
        return value instanceof List<?> list && list.size() <= MAX_LIST ? List.copyOf(list) : value;
    }

    private static String format(Object value) {
        return value == null
                ? "null"
                : value instanceof Enum<?> e
                        ? e.name()
                        : value instanceof List<?> list
                                ? Integer.toString(list.size())
                                : kind(value) == Kind.UNSUPPORTED
                                        ? value.getClass().getSimpleName()
                                        : String.valueOf(value);
    }

    private static Input input(Object value) {
        if (value instanceof List<?> list && list.size() <= MAX_LIST)
            return Input.list(list.stream().map(ConfigEditor::format).toList());
        return Input.scalar(format(value));
    }
}
