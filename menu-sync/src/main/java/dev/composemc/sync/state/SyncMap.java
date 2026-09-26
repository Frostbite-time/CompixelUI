package dev.composemc.sync.state;

import java.util.*;

/**
 * Immutable keyed snapshots. Updates share unchanged tree branches; differences skip shared branches.
 * Keys and values must be immutable and non-null. Iteration order is unspecified, as with synchronized keyed collections.
 */
public final class SyncMap<K, V> extends AbstractMap<K, V> {
    @FunctionalInterface
    public interface ChangeConsumer<K, V> {
        /** A null previous/current value denotes insertion/removal respectively. */
        void accept(K key, V previous, V current);
    }

    private static final SyncMap<?, ?> EMPTY = new SyncMap<>(null);
    private final Node<K, V> root;

    private SyncMap(Node<K, V> root) {
        this.root = root;
    }

    @SuppressWarnings("unchecked")
    public static <K, V> SyncMap<K, V> empty() {
        return (SyncMap<K, V>) EMPTY;
    }

    public static <K, V> SyncMap<K, V> copyOf(Map<? extends K, ? extends V> values) {
        SyncMap<K, V> result = empty();
        for (var entry : values.entrySet()) result = result.with(entry.getKey(), entry.getValue());
        return result;
    }

    /** Return the same snapshot for an equal value; otherwise retain a new immutable snapshot. */
    public SyncMap<K, V> with(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        Node<K, V> next = put(root, key.hashCode(), key, value);
        return next == root ? this : new SyncMap<>(next);
    }

    public SyncMap<K, V> without(K key) {
        Objects.requireNonNull(key);
        Node<K, V> next = remove(root, key.hashCode(), key);
        return next == root ? this : next == null ? empty() : new SyncMap<>(next);
    }

    @Override
    public int size() {
        return size(root);
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public V get(Object key) {
        if (key == null) return null;
        int hash = key.hashCode();
        Node<K, V> node = root;
        while (node != null) {
            int comparison = Integer.compare(hash, node.hash);
            if (comparison < 0) node = node.left;
            else if (comparison > 0) node = node.right;
            else {
                for (var entry : node.entries) if (entry.getKey().equals(key)) return entry.getValue();
                return null;
            }
        }
        return null;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() {
                return SyncMap.this.size();
            }

            @Override
            public Iterator<Entry<K, V>> iterator() {
                return new Iterator<>() {
                    final ArrayDeque<Node<K, V>> stack = new ArrayDeque<>();
                    Iterator<Entry<K, V>> bucket = Collections.emptyIterator();

                    {
                        descend(root);
                    }

                    private void descend(Node<K, V> node) {
                        while (node != null) {
                            stack.push(node);
                            node = node.left;
                        }
                    }

                    @Override
                    public boolean hasNext() {
                        return bucket.hasNext() || !stack.isEmpty();
                    }

                    @Override
                    public Entry<K, V> next() {
                        if (!bucket.hasNext()) {
                            if (stack.isEmpty()) throw new NoSuchElementException();
                            var node = stack.pop();
                            bucket = node.entries.iterator();
                            descend(node.right);
                        }
                        return bucket.next();
                    }
                };
            }
        };
    }

    /** Compare arbitrary snapshots, without retaining a chain of historical revisions. */
    public void forEachChange(SyncMap<K, V> previous, ChangeConsumer<? super K, ? super V> consumer) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(consumer);
        if (root == previous.root) return;
        var before = new ArrayDeque<Step<K, V>>();
        var after = new ArrayDeque<Step<K, V>>();
        if (previous.root != null) before.push(new Step<>(previous.root, false));
        if (root != null) after.push(new Step<>(root, false));
        while (!before.isEmpty() || !after.isEmpty()) {
            var old = before.peek();
            var next = after.peek();
            if (old != null && next != null && old.node == next.node && old.bucket == next.bucket) {
                before.pop();
                after.pop();
                continue;
            }
            boolean expanded = false;
            if (old != null && !old.bucket) {
                expand(before);
                expanded = true;
            }
            if (next != null && !next.bucket) {
                expand(after);
                expanded = true;
            }
            if (expanded) continue;
            int comparison = old == null ? 1 : next == null ? -1 : Integer.compare(old.node.hash, next.node.hash);
            if (comparison < 0) {
                for (var entry : before.pop().node.entries) consumer.accept(entry.getKey(), entry.getValue(), null);
            } else if (comparison > 0) {
                for (var entry : after.pop().node.entries) consumer.accept(entry.getKey(), null, entry.getValue());
            } else {
                var a = before.pop().node.entries;
                var b = after.pop().node.entries;
                if (a == b) continue;
                for (var entry : a) {
                    V value = find(b, entry.getKey());
                    if (!Objects.equals(entry.getValue(), value))
                        consumer.accept(entry.getKey(), entry.getValue(), value);
                }
                for (var entry : b)
                    if (find(a, entry.getKey()) == null) consumer.accept(entry.getKey(), null, entry.getValue());
            }
        }
    }

    private static <K, V> V find(List<Entry<K, V>> entries, K key) {
        for (var entry : entries) if (entry.getKey().equals(key)) return entry.getValue();
        return null;
    }

    private record Step<K, V>(Node<K, V> node, boolean bucket) {}

    private static <K, V> void expand(ArrayDeque<Step<K, V>> stack) {
        Node<K, V> node = stack.pop().node;
        if (node.right != null) stack.push(new Step<>(node.right, false));
        stack.push(new Step<>(node, true));
        if (node.left != null) stack.push(new Step<>(node.left, false));
    }

    private static final class Node<K, V> {
        final int hash, height, size;
        final List<Entry<K, V>> entries;
        final Node<K, V> left, right;

        Node(int hash, List<Entry<K, V>> entries, Node<K, V> left, Node<K, V> right) {
            this.hash = hash;
            this.entries = entries;
            this.left = left;
            this.right = right;
            height = 1 + Math.max(height(left), height(right));
            size = entries.size() + size(left) + size(right);
        }
    }

    private static int height(Node<?, ?> node) {
        return node == null ? 0 : node.height;
    }

    private static int size(Node<?, ?> node) {
        return node == null ? 0 : node.size;
    }

    private static <K, V> Node<K, V> put(Node<K, V> node, int hash, K key, V value) {
        if (node == null) return new Node<>(hash, List.of(new SimpleImmutableEntry<>(key, value)), null, null);
        int comparison = Integer.compare(hash, node.hash);
        if (comparison == 0) {
            var entries = new ArrayList<>(node.entries);
            for (int i = 0; i < entries.size(); i++)
                if (entries.get(i).getKey().equals(key)) {
                    if (Objects.equals(entries.get(i).getValue(), value)) return node;
                    entries.set(i, new SimpleImmutableEntry<>(key, value));
                    return new Node<>(hash, List.copyOf(entries), node.left, node.right);
                }
            entries.add(new SimpleImmutableEntry<>(key, value));
            return new Node<>(hash, List.copyOf(entries), node.left, node.right);
        }
        if (comparison < 0) {
            var left = put(node.left, hash, key, value);
            return left == node.left ? node : balance(new Node<>(node.hash, node.entries, left, node.right));
        }
        var right = put(node.right, hash, key, value);
        return right == node.right ? node : balance(new Node<>(node.hash, node.entries, node.left, right));
    }

    private static <K, V> Node<K, V> remove(Node<K, V> node, int hash, K key) {
        if (node == null) return null;
        int comparison = Integer.compare(hash, node.hash);
        if (comparison == 0) {
            var entries = new ArrayList<>(node.entries);
            if (!entries.removeIf(entry -> entry.getKey().equals(key))) return node;
            if (!entries.isEmpty()) return new Node<>(hash, List.copyOf(entries), node.left, node.right);
            if (node.left == null) return node.right;
            if (node.right == null) return node.left;
            var successor = node.right;
            while (successor.left != null) successor = successor.left;
            return balance(new Node<>(successor.hash, successor.entries, node.left, removeMin(node.right)));
        }
        if (comparison < 0) {
            var left = remove(node.left, hash, key);
            return left == node.left ? node : balance(new Node<>(node.hash, node.entries, left, node.right));
        }
        var right = remove(node.right, hash, key);
        return right == node.right ? node : balance(new Node<>(node.hash, node.entries, node.left, right));
    }

    private static <K, V> Node<K, V> removeMin(Node<K, V> node) {
        return node.left == null
                ? node.right
                : balance(new Node<>(node.hash, node.entries, removeMin(node.left), node.right));
    }

    private static <K, V> Node<K, V> balance(Node<K, V> node) {
        if (height(node.left) - height(node.right) > 1) {
            if (height(node.left.left) < height(node.left.right))
                node = new Node<>(node.hash, node.entries, rotateLeft(node.left), node.right);
            return rotateRight(node);
        }
        if (height(node.right) - height(node.left) > 1) {
            if (height(node.right.right) < height(node.right.left))
                node = new Node<>(node.hash, node.entries, node.left, rotateRight(node.right));
            return rotateLeft(node);
        }
        return node;
    }

    private static <K, V> Node<K, V> rotateLeft(Node<K, V> node) {
        var top = node.right;
        return new Node<>(top.hash, top.entries, new Node<>(node.hash, node.entries, node.left, top.left), top.right);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
        var top = node.left;
        return new Node<>(top.hash, top.entries, top.left, new Node<>(node.hash, node.entries, top.right, node.right));
    }
}
