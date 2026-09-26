package dev.composemc.slots;

import java.util.*;

/**
 * Named, disjoint groups of stable menu slot IDs and ordered quick-transfer destinations.
 * Build once after adding slots. Routes select destinations only; the server's executor still
 * checks extraction/insertion permissions, capacity, resource identity and transaction hooks.
 */
public final class SlotTransferRoutes {
    private final List<Integer>[] bySource;

    private SlotTransferRoutes(List<Integer>[] bySource) {
        this.bySource = bySource;
    }

    /** Immutable cached list, no per-click expansion/allocation. Invalid or unrouted IDs yield empty. */
    public List<Integer> targets(int sourceSlot) {
        return sourceSlot >= 0 && sourceSlot < bySource.length ? bySource[sourceSlot] : List.of();
    }

    public static Builder builder(int slotCount) {
        return new Builder(slotCount);
    }

    public static final class Builder {
        private final int slotCount;
        private final Map<String, List<Integer>> groups = new LinkedHashMap<>();
        private final Map<String, List<String>> routes = new LinkedHashMap<>();

        private Builder(int slotCount) {
            if (slotCount < 0 || slotCount > 65_536) throw new IllegalArgumentException("Invalid slot count");
            this.slotCount = slotCount;
        }

        public Builder group(String name, int startInclusive, int endExclusive) {
            return group(name, startInclusive, endExclusive, false);
        }

        /** Reverse controls destination iteration order; it never changes protocol slot IDs. */
        public Builder group(String name, int startInclusive, int endExclusive, boolean reverse) {
            Objects.requireNonNull(name);
            if (name.isBlank() || groups.containsKey(name))
                throw new IllegalArgumentException("Duplicate/empty group: " + name);
            if (startInclusive < 0 || startInclusive >= endExclusive || endExclusive > slotCount)
                throw new IllegalArgumentException("Invalid group range: " + name);
            var ids = new ArrayList<Integer>(endExclusive - startInclusive);
            for (int i = startInclusive; i < endExclusive; i++)
                ids.add(reverse ? endExclusive - 1 - (i - startInclusive) : i);
            groups.put(name, List.copyOf(ids));
            return this;
        }

        /** Destinations are attempted in this order. A source may have only one declared route. */
        public Builder route(String source, String... destinations) {
            Objects.requireNonNull(source);
            var names = List.of(destinations);
            if (names.isEmpty() || new HashSet<>(names).size() != names.size() || names.contains(source))
                throw new IllegalArgumentException("Empty, repeated or self destination");
            if (routes.putIfAbsent(source, names) != null)
                throw new IllegalArgumentException("Duplicate route: " + source);
            return this;
        }

        @SuppressWarnings("unchecked")
        public SlotTransferRoutes build() {
            var owners = new String[slotCount];
            groups.forEach((name, ids) -> ids.forEach(id -> {
                if (owners[id] != null) throw new IllegalArgumentException("Overlapping groups at slot " + id);
                owners[id] = name;
            }));
            List<Integer>[] compiled = (List<Integer>[]) new List<?>[slotCount];
            Arrays.fill(compiled, List.of());
            long expandedTargets = 0;
            for (var entry : routes.entrySet()) {
                var sources = requireGroup(entry.getKey());
                var targets = new ArrayList<Integer>();
                for (String destination : entry.getValue()) targets.addAll(requireGroup(destination));
                expandedTargets += targets.size();
                if (expandedTargets > 1_000_000) throw new IllegalArgumentException("Transfer route budget exceeded");
                var immutable = List.copyOf(targets);
                for (int source : sources) compiled[source] = immutable;
            }
            return new SlotTransferRoutes(compiled);
        }

        private List<Integer> requireGroup(String name) {
            var group = groups.get(name);
            if (group == null) throw new IllegalArgumentException("Unknown group: " + name);
            return group;
        }
    }
}
