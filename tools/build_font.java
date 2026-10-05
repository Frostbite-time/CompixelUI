import static java.lang.Character.UnicodeBlock.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/**
 * Builds the Compixel font that ui-ore ships: Monocraft's glyphs unchanged, and GNU Unifont for the other characters of
 * the languages Minecraft offers.
 *
 * <pre>java tools/build_font.java Monocraft.ttf unifont.hex out.ttf</pre>
 *
 * Inputs: Monocraft 4.2's Monocraft.ttf (https://github.com/IdreesInc/Monocraft), and GNU Unifont 17.0.01's
 * "unifont_all" hex file, plain or zipped (https://unifoundry.com/unifont/). Minecraft 26.x ships the same Unifont as
 * unifont_all_no_pua-17.0.01.hex in its assets. Run it with JDK 25: its character data decides which characters are
 * marks and which are emoji, so other versions may build a slightly different font.
 *
 * Unifont pixels are half a Monocraft pixel, and glyphs are trimmed to their inked columns plus one Monocraft pixel of
 * spacing, as Minecraft draws Unifont next to its own font. The font takes the Unicode blocks of the scripts Minecraft's
 * languages use, with common punctuation and symbols, from the Basic Multilingual Plane only. It leaves to the system's
 * fonts: emoji, which look better in color; Devanagari, Tamil and Kannada, which need reordering and conjuncts that
 * pixel glyphs cannot form; and rarer scripts and characters, such as CJK extensions.
 */
public class build_font {
    static final int PIXEL = 60; // Half of Monocraft's pixel, which is 120 units of its 1080-unit em.
    static final int SPACING = 2; // In Unifont pixels, after each glyph.
    static final int BASELINE = 14; // Unifont rows above the baseline.

    static final Set<Character.UnicodeBlock> BLOCKS = Set.of(
            // Latin, Greek, Cyrillic, Armenian, Georgian
            BASIC_LATIN, LATIN_1_SUPPLEMENT, LATIN_EXTENDED_A, LATIN_EXTENDED_B, IPA_EXTENSIONS,
            SPACING_MODIFIER_LETTERS, COMBINING_DIACRITICAL_MARKS, PHONETIC_EXTENSIONS, PHONETIC_EXTENSIONS_SUPPLEMENT,
            LATIN_EXTENDED_ADDITIONAL, LATIN_EXTENDED_C, LATIN_EXTENDED_D, LATIN_EXTENDED_E, ALPHABETIC_PRESENTATION_FORMS,
            GREEK, GREEK_EXTENDED, CYRILLIC, CYRILLIC_SUPPLEMENTARY, ARMENIAN, GEORGIAN, GEORGIAN_EXTENDED,
            // Hebrew, Arabic, Thai, Lao
            HEBREW, ARABIC, ARABIC_SUPPLEMENT, ARABIC_PRESENTATION_FORMS_A, ARABIC_PRESENTATION_FORMS_B, THAI, LAO,
            // Chinese, Japanese, Korean
            CJK_RADICALS_SUPPLEMENT, KANGXI_RADICALS, CJK_SYMBOLS_AND_PUNCTUATION, HIRAGANA, KATAKANA,
            KATAKANA_PHONETIC_EXTENSIONS, BOPOMOFO, BOPOMOFO_EXTENDED, KANBUN, ENCLOSED_CJK_LETTERS_AND_MONTHS,
            CJK_COMPATIBILITY, CJK_UNIFIED_IDEOGRAPHS, CJK_COMPATIBILITY_IDEOGRAPHS, HANGUL_JAMO,
            HANGUL_COMPATIBILITY_JAMO, HANGUL_JAMO_EXTENDED_A, HANGUL_JAMO_EXTENDED_B, HANGUL_SYLLABLES, VERTICAL_FORMS,
            CJK_COMPATIBILITY_FORMS, SMALL_FORM_VARIANTS, HALFWIDTH_AND_FULLWIDTH_FORMS,
            // Punctuation and symbols
            GENERAL_PUNCTUATION, SUPPLEMENTAL_PUNCTUATION, SUPERSCRIPTS_AND_SUBSCRIPTS, CURRENCY_SYMBOLS,
            LETTERLIKE_SYMBOLS, NUMBER_FORMS, ARROWS, MATHEMATICAL_OPERATORS, MISCELLANEOUS_TECHNICAL,
            ENCLOSED_ALPHANUMERICS, BOX_DRAWING, BLOCK_ELEMENTS, GEOMETRIC_SHAPES, MISCELLANEOUS_SYMBOLS, DINGBATS,
            MISCELLANEOUS_SYMBOLS_AND_ARROWS, SPECIALS);

    // Monocraft has only a few glyphs here, which would stand out among Unifont's or the system's.
    static final Set<Character.UnicodeBlock> NOT_MONOCRAFT = Set.of(
            CJK_SYMBOLS_AND_PUNCTUATION, BOPOMOFO, HALFWIDTH_AND_FULLWIDTH_FORMS, NKO, UNIFIED_CANADIAN_ABORIGINAL_SYLLABICS);

    // Arabic letters join across whole cells, so they keep them and get no spacing.
    static final Set<Character.UnicodeBlock> JOINED =
            Set.of(ARABIC, ARABIC_SUPPLEMENT, ARABIC_PRESENTATION_FORMS_A, ARABIC_PRESENTATION_FORMS_B);

    // Characters that never show: control characters but the tab, which text layout turns into space, the BMP's
    // Default_Ignorable_Code_Point, the line and paragraph separators and the interlinear annotation controls. Without
    // a glyph, a stray carriage return draws a missing-glyph box; Unifont draws most of the others as labeled boxes.
    static final int[][] INVISIBLE = {
        {0x0000, 0x0008}, {0x000A, 0x001F}, {0x007F, 0x009F},
        {0x00AD, 0x00AD}, {0x034F, 0x034F}, {0x061C, 0x061C}, {0x115F, 0x1160}, {0x17B4, 0x17B5}, {0x180B, 0x180F},
        {0x200B, 0x200F}, {0x2028, 0x202E}, {0x2060, 0x206F}, {0x3164, 0x3164}, {0xFE00, 0xFE0F}, {0xFEFF, 0xFEFF},
        {0xFFA0, 0xFFA0}, {0xFFF0, 0xFFFB}
    };

    // The hex digits Unifont writes into the box it draws for code points it has no glyph for, as five rows of four
    // pixels.
    static final int[] DIGITS = {
        0x69996, 0x26227, 0xF1F8F, 0xE171E, 0x99F11, 0xF8F1F, 0x68E96, 0xF1244,
        0x69696, 0x69716, 0xF9F99, 0xE9E9E, 0x78887, 0xE999E, 0xF8E8F, 0xF8E88
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("usage: build_font <Monocraft.ttf> <unifont.hex|zip> <out.ttf>");
        if (Runtime.version().feature() != 25) throw new IllegalStateException("Run with JDK 25");

        Font font = Font.read(ByteBuffer.wrap(Files.readAllBytes(Path.of(args[0]))));
        TreeMap<Integer, String> unifont = readHex(Path.of(args[1]));

        int monocraft = font.cmap.size();
        font.cmap.keySet().removeIf(c -> Character.isEmojiPresentation(c) || in(NOT_MONOCRAFT, c));
        int empty = font.add(new Glyph(new byte[0], 0));
        for (int[] range : INVISIBLE) for (int c = range[0]; c <= range[1]; c++) font.cmap.put(c, empty);
        int fromMonocraft = (int) font.cmap.values().stream().filter(g -> g != empty).count();

        int placeholders = 0, added = 0;
        for (var e : unifont.headMap(0x10000).entrySet()) {
            int c = e.getKey();
            if (font.cmap.containsKey(c) || !wanted(c)) continue;
            if (placeholder(c, e.getValue())) {
                placeholders++;
                continue;
            }
            font.cmap.put(c, font.add(glyph(c, e.getValue())));
            added++;
        }

        byte[] bytes = font.write();
        Files.write(Path.of(args[2]), bytes);
        System.out.printf("%s: %,d bytes, %,d glyphs for %,d characters%n", args[2], bytes.length, font.glyphs.size(), font.cmap.size());
        System.out.printf("Characters: %,d of Monocraft's %,d, %,d from Unifont, %d invisible. Skipped %d Unifont placeholders.%n",
                fromMonocraft, monocraft, added, font.cmap.size() - fromMonocraft - added, placeholders);
    }

    static TreeMap<Integer, String> readHex(Path path) throws IOException {
        String text;
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > 1 && bytes[0] == 'P' && bytes[1] == 'K') {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                var entry = zip.stream().filter(e -> e.getName().endsWith(".hex")).findFirst().orElseThrow();
                text = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.US_ASCII);
            }
        } else text = new String(bytes, StandardCharsets.US_ASCII);
        TreeMap<Integer, String> glyphs = new TreeMap<>();
        for (String line : text.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) glyphs.put(Integer.parseInt(line.substring(0, colon), 16), line.substring(colon + 1).trim());
        }
        return glyphs;
    }

    static boolean in(Set<Character.UnicodeBlock> blocks, int c) {
        var block = Character.UnicodeBlock.of(c);
        return block != null && blocks.contains(block);
    }

    static boolean wanted(int c) {
        int type = Character.getType(c);
        boolean noncharacter = (c >= 0xFDD0 && c <= 0xFDEF) || (c & 0xFFFE) == 0xFFFE;
        return in(BLOCKS, c)
                && type != Character.CONTROL
                && type != Character.PRIVATE_USE
                && type != Character.SURROGATE
                && !noncharacter
                && !Character.isEmojiPresentation(c);
    }

    // Whether Unifont drew its stand-in for a character it has no glyph for: the code point's four hex digits, unlit
    // in a lit 14-pixel frame.
    static boolean placeholder(int c, String bits) {
        if (bits.length() != 64) return false;
        for (int r = 0; r < 16; r++) {
            int expected = r == 0 || r == 15 ? 0 : 0x7FFE;
            int line = r >= 2 && r <= 6 ? r - 2 : r >= 9 && r <= 13 ? r - 9 : -1;
            if (line >= 0) {
                int first = r <= 6 ? 12 : 4; // The digits' shift in the code point: two above, two below.
                for (int i = 0; i < 2; i++) {
                    int digit = c >> (first - 4 * i) & 0xF;
                    expected &= ~((DIGITS[digit] >> (4 * (4 - line)) & 0xF) << (i == 0 ? 9 : 3));
                }
            }
            if (Integer.parseInt(bits.substring(r * 4, r * 4 + 4), 16) != expected) return false;
        }
        return true;
    }

    // ---- Unifont glyphs ----

    // Minecraft keeps the whole cell for these, and trims every other glyph to its inked columns.
    static boolean fullCell(int c) {
        return (c >= 0x3001 && c <= 0x30FF)
                || (c >= 0x3200 && c <= 0x9FFF)
                || (c >= 0x1100 && c <= 0x11FF)
                || (c >= 0x3130 && c <= 0x318F)
                || (c >= 0xA960 && c <= 0xA97F)
                || (c >= 0xD7B0 && c <= 0xD7FF)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFF01 && c <= 0xFF5E);
    }

    static Glyph glyph(int c, String bits) {
        int width = bits.length() / 4;
        boolean[][] lit = new boolean[16][width];
        int left = width, right = -1;
        for (int r = 0; r < 16; r++) {
            int row = Integer.parseInt(bits.substring(r * width / 4, (r + 1) * width / 4), 16);
            for (int x = 0; x < width; x++) {
                lit[r][x] = (row >> (width - 1 - x) & 1) != 0;
                if (lit[r][x]) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
        }
        boolean joined = in(JOINED, c);
        int type = Character.getType(c);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) {
            // Unifont draws a mark over a base in the same cell. Without advancing, shift it over the previous
            // glyph, whose usual extent ends one pixel before the cell's (two for wide cells) plus the spacing.
            int shift = joined ? width : width + (width == 8 ? 1 : 2);
            return new Glyph(outline(lit, shift), 0);
        }
        if (joined) return new Glyph(outline(lit, 0), width * PIXEL);
        if (c >= 0xAC00 && c <= 0xD7AF) { // Minecraft drops the first column of Hangul syllables.
            left = 1;
            right = width - 1;
        } else if (right < 0 || fullCell(c)) {
            left = 0;
            right = width - 1;
        }
        return new Glyph(outline(lit, left), (right - left + 1 + SPACING) * PIXEL);
    }

    // The lit pixels' outlines, clockwise with y up so that TrueType fills them, starting `left` pixels in.
    static List<List<int[]>> outline(boolean[][] lit, int left) {
        int width = lit[0].length;
        List<int[]> edges = new ArrayList<>(); // x0, y0, x1, y1
        for (int r = 0; r < 16; r++)
            for (int x = 0; x < width; x++) {
                if (!lit[r][x]) continue;
                int top = BASELINE - r, bottom = top - 1;
                if (r == 0 || !lit[r - 1][x]) edges.add(new int[] {x, top, x + 1, top});
                if (x == width - 1 || !lit[r][x + 1]) edges.add(new int[] {x + 1, top, x + 1, bottom});
                if (r == 15 || !lit[r + 1][x]) edges.add(new int[] {x + 1, bottom, x, bottom});
                if (x == 0 || !lit[r][x - 1]) edges.add(new int[] {x, bottom, x, top});
            }
        Map<Long, List<int[]>> from = new HashMap<>();
        for (int[] e : edges) from.computeIfAbsent(key(e[0], e[1]), k -> new ArrayList<>()).add(e);
        Set<int[]> used = Collections.newSetFromMap(new IdentityHashMap<>());
        List<List<int[]>> contours = new ArrayList<>();
        for (int[] first : edges) {
            if (used.contains(first)) continue;
            List<int[]> points = new ArrayList<>();
            for (int[] e = first; e != null && used.add(e); ) {
                points.add(e);
                int dx = Integer.signum(e[2] - e[0]), dy = Integer.signum(e[3] - e[1]);
                int[] next = null;
                // Where two pixels touch only at a corner, turn right, so that each keeps its own outline.
                for (int[] candidate : from.get(key(e[2], e[3]))) {
                    if (used.contains(candidate)) continue;
                    if (Integer.signum(candidate[2] - candidate[0]) == dy
                            && Integer.signum(candidate[3] - candidate[1]) == -dx) {
                        next = candidate;
                        break;
                    }
                    if (next == null) next = candidate;
                }
                e = next;
            }
            List<int[]> corners = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                int[] prev = points.get((i + points.size() - 1) % points.size()), cur = points.get(i);
                boolean straight = (prev[0] == prev[2]) == (cur[0] == cur[2]);
                if (!straight) corners.add(new int[] {(cur[0] - left) * PIXEL, cur[1] * PIXEL});
            }
            contours.add(corners);
        }
        return contours;
    }

    static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
    }

    record Glyph(byte[] data, int advance) {
        Glyph(List<List<int[]>> contours, int advance) {
            this(encode(contours), advance);
        }

        int xMin() {
            return data.length == 0 ? 0 : ByteBuffer.wrap(data).getShort(2);
        }
    }

    // A simple glyph of on-curve points, without instructions.
    static byte[] encode(List<List<int[]>> contours) {
        if (contours.isEmpty()) return new byte[0];
        int xMin = Integer.MAX_VALUE, yMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE, yMax = Integer.MIN_VALUE;
        for (var contour : contours)
            for (int[] p : contour) {
                xMin = Math.min(xMin, p[0]);
                yMin = Math.min(yMin, p[1]);
                xMax = Math.max(xMax, p[0]);
                yMax = Math.max(yMax, p[1]);
            }
        List<Integer> flags = new ArrayList<>();
        ByteArrayOutputStream xs = new ByteArrayOutputStream(), ys = new ByteArrayOutputStream();
        int px = 0, py = 0;
        for (var contour : contours)
            for (int[] p : contour) {
                int dx = p[0] - px, dy = p[1] - py;
                px = p[0];
                py = p[1];
                flags.add(0x01 | coordinate(dx, xs, 0x02, 0x10) | coordinate(dy, ys, 0x04, 0x20));
            }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        try {
            out.writeShort(contours.size());
            out.writeShort(xMin);
            out.writeShort(yMin);
            out.writeShort(xMax);
            out.writeShort(yMax);
            int end = -1;
            for (var contour : contours) out.writeShort(end += contour.size());
            out.writeShort(0);
            for (int i = 0; i < flags.size(); ) {
                int flag = flags.get(i), repeats = 0;
                while (i + 1 + repeats < flags.size() && flags.get(i + 1 + repeats) == flag && repeats < 255) repeats++;
                if (repeats > 0) out.write(new byte[] {(byte) (flag | 0x08), (byte) repeats});
                else out.write(flag);
                i += 1 + repeats;
            }
            xs.writeTo(out);
            ys.writeTo(out);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        while (body.size() % 4 != 0) body.write(0);
        return body.toByteArray();
    }

    // Writes one coordinate delta and returns its flag bits.
    static int coordinate(int delta, ByteArrayOutputStream out, int shortFlag, int sameFlag) {
        if (delta == 0) return sameFlag;
        if (Math.abs(delta) < 256) {
            out.write(Math.abs(delta));
            return shortFlag | (delta > 0 ? sameFlag : 0);
        }
        out.write(delta >> 8 & 0xFF);
        out.write(delta & 0xFF);
        return 0;
    }

    // ---- The TrueType font ----

    static final class Font {
        final Map<String, byte[]> tables;
        final TreeMap<Integer, Integer> cmap;
        final List<Glyph> glyphs = new ArrayList<>();

        Font(Map<String, byte[]> tables, TreeMap<Integer, Integer> cmap) {
            this.tables = tables;
            this.cmap = cmap;
        }

        int add(Glyph glyph) {
            glyphs.add(glyph);
            if (glyphs.size() > 0xFFFF) throw new IllegalStateException("More glyphs than a TrueType font holds");
            return glyphs.size() - 1;
        }

        static Font read(ByteBuffer b) {
            Map<String, byte[]> tables = new TreeMap<>();
            for (int i = 0, count = b.getShort(4) & 0xFFFF; i < count; i++) {
                int at = 12 + i * 16;
                byte[] tag = new byte[4], data = new byte[b.getInt(at + 12)];
                b.get(at, tag).get(b.getInt(at + 8), data);
                tables.put(new String(tag, StandardCharsets.US_ASCII), data);
            }
            Font font = new Font(tables, readCmap(ByteBuffer.wrap(tables.get("cmap"))));
            ByteBuffer loca = ByteBuffer.wrap(tables.get("loca")), glyf = ByteBuffer.wrap(tables.get("glyf"));
            ByteBuffer hmtx = ByteBuffer.wrap(tables.get("hmtx"));
            boolean longLoca = ByteBuffer.wrap(tables.get("head")).getShort(50) == 1;
            int count = ByteBuffer.wrap(tables.get("maxp")).getShort(4) & 0xFFFF;
            int metrics = ByteBuffer.wrap(tables.get("hhea")).getShort(34) & 0xFFFF;
            for (int g = 0; g < count; g++) {
                int start = longLoca ? loca.getInt(g * 4) : (loca.getShort(g * 2) & 0xFFFF) * 2;
                int end = longLoca ? loca.getInt(g * 4 + 4) : (loca.getShort(g * 2 + 2) & 0xFFFF) * 2;
                byte[] data = new byte[(end - start + 3) / 4 * 4];
                glyf.get(start, data, 0, end - start);
                int advance = hmtx.getShort(Math.min(g, metrics - 1) * 4) & 0xFFFF;
                font.glyphs.add(new Glyph(data, advance));
            }
            return font;
        }

        static TreeMap<Integer, Integer> readCmap(ByteBuffer b) {
            for (int i = 0, count = b.getShort(2) & 0xFFFF; i < count; i++) {
                int at = 4 + i * 8;
                if (b.getShort(at) != 3 || b.getShort(at + 2) != 1) continue;
                int t = b.getInt(at + 4);
                int segs = (b.getShort(t + 6) & 0xFFFF) / 2;
                int ends = t + 14, starts = ends + segs * 2 + 2, deltas = starts + segs * 2, ranges = deltas + segs * 2;
                TreeMap<Integer, Integer> map = new TreeMap<>();
                for (int s = 0; s < segs; s++) {
                    int end = b.getShort(ends + s * 2) & 0xFFFF, start = b.getShort(starts + s * 2) & 0xFFFF;
                    int delta = b.getShort(deltas + s * 2), range = b.getShort(ranges + s * 2) & 0xFFFF;
                    for (int c = start; c <= end && c != 0xFFFF; c++) {
                        int g = range == 0 ? c : b.getShort(ranges + s * 2 + range + (c - start) * 2) & 0xFFFF;
                        if (range == 0 || g != 0) g = (g + delta) & 0xFFFF;
                        if (g != 0) map.put(c, g);
                    }
                }
                return map;
            }
            throw new IllegalStateException("Monocraft has no Windows Unicode cmap");
        }

        byte[] write() throws IOException {
            Map<String, byte[]> out = new TreeMap<>(tables);
            out.remove("FFTM"); // FontForge's build time of Monocraft

            ByteArrayOutputStream glyf = new ByteArrayOutputStream();
            ByteBuffer loca = ByteBuffer.allocate((glyphs.size() + 1) * 4);
            ByteBuffer hmtx = ByteBuffer.allocate(glyphs.size() * 4);
            int xMin = 0, yMin = 0, xMax = 0, yMax = 0, maxPoints = 0, maxContours = 0;
            int minLsb = 0, minRsb = 0, maxExtent = 0, maxAdvance = 0;
            for (Glyph g : glyphs) {
                loca.putInt(glyf.size());
                glyf.write(g.data);
                hmtx.putShort((short) g.advance).putShort((short) g.xMin());
                maxAdvance = Math.max(maxAdvance, g.advance);
                if (g.data.length == 0) continue;
                ByteBuffer d = ByteBuffer.wrap(g.data);
                int contours = d.getShort(0), x0 = d.getShort(2), y0 = d.getShort(4), x1 = d.getShort(6);
                xMin = Math.min(xMin, x0);
                yMin = Math.min(yMin, y0);
                xMax = Math.max(xMax, x1);
                yMax = Math.max(yMax, d.getShort(8));
                minLsb = Math.min(minLsb, x0);
                minRsb = Math.min(minRsb, g.advance - x1);
                maxExtent = Math.max(maxExtent, x1);
                maxContours = Math.max(maxContours, contours);
                maxPoints = Math.max(maxPoints, (d.getShort(10 + (contours - 1) * 2) & 0xFFFF) + 1);
            }
            loca.putInt(glyf.size());
            out.put("glyf", glyf.toByteArray());
            out.put("loca", loca.array());
            out.put("hmtx", hmtx.array());

            ByteBuffer head = ByteBuffer.wrap(tables.get("head").clone());
            head.putInt(8, 0); // checkSumAdjustment, set last
            head.putShort(36, (short) xMin).putShort(38, (short) yMin).putShort(40, (short) xMax).putShort(42, (short) yMax);
            head.putShort(50, (short) 1);
            out.put("head", head.array());

            ByteBuffer hhea = ByteBuffer.wrap(tables.get("hhea").clone());
            hhea.putShort(10, (short) maxAdvance).putShort(12, (short) minLsb).putShort(14, (short) minRsb);
            hhea.putShort(16, (short) maxExtent).putShort(34, (short) glyphs.size());
            out.put("hhea", hhea.array());

            ByteBuffer maxp = ByteBuffer.wrap(tables.get("maxp").clone());
            maxp.putShort(4, (short) glyphs.size());
            maxp.putShort(6, (short) Math.max(maxp.getShort(6) & 0xFFFF, maxPoints));
            maxp.putShort(8, (short) Math.max(maxp.getShort(8) & 0xFFFF, maxContours));
            out.put("maxp", maxp.array());

            ByteBuffer os2 = ByteBuffer.wrap(tables.get("OS/2").clone());
            for (int bit : new int[] {0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 13, 24, 25, 26, 28, 29, 30, 31, 32, 33, 35, 36, 37,
                    38, 39, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 54, 55, 56, 59, 61, 62, 63, 65, 66, 67, 68, 69}) {
                int at = 42 + bit / 32 * 4;
                os2.putInt(at, os2.getInt(at) | 1 << bit % 32);
            }
            os2.put(35, (byte) 0); // Panose proportion: no longer monospaced
            os2.putShort(64, (short) (int) cmap.firstKey()).putShort(66, (short) (int) cmap.lastKey());
            // Cyrillic, Greek, Hebrew, Arabic, Thai, Japanese, Chinese and Korean code pages
            os2.putInt(78, os2.getInt(78) | 1 << 2 | 1 << 3 | 1 << 5 | 1 << 6 | 1 << 16 | 1 << 17 | 1 << 18 | 1 << 19 | 1 << 20);
            out.put("OS/2", os2.array());

            ByteBuffer post = ByteBuffer.allocate(32).put(0, tables.get("post"), 0, 32);
            post.putInt(0, 0x00030000).putInt(12, 0); // No glyph names, not fixed pitch
            out.put("post", post.array());

            out.put("cmap", cmap());
            out.put("name", name());
            return assemble(out);
        }

        // Format 4 of the Basic Multilingual Plane, under Unicode and Windows Unicode.
        byte[] cmap() throws IOException {
            List<int[]> segments = new ArrayList<>(); // first code point, last code point, first glyph
            for (var e : cmap.entrySet()) {
                int[] last = segments.isEmpty() ? null : segments.get(segments.size() - 1);
                if (last != null && last[1] == e.getKey() - 1 && last[2] + e.getKey() - last[0] == e.getValue()) {
                    last[1] = e.getKey();
                } else segments.add(new int[] {e.getKey(), e.getKey(), e.getValue()});
            }
            segments.add(new int[] {0xFFFF, 0xFFFF, 0});
            int segs = segments.size(), selector = 31 - Integer.numberOfLeadingZeros(segs), range = 2 << selector;
            if (16 + segs * 8 > 0xFFFF) throw new IllegalStateException("Too many cmap segments: " + segs);

            ByteArrayOutputStream table = new ByteArrayOutputStream();
            DataOutputStream t = new DataOutputStream(table);
            t.writeShort(0);
            t.writeShort(2);
            for (int platform : new int[] {0, 3}) {
                t.writeShort(platform);
                t.writeShort(platform == 0 ? 3 : 1);
                t.writeInt(4 + 2 * 8);
            }
            t.writeShort(4);
            t.writeShort(16 + segs * 8);
            t.writeShort(0);
            t.writeShort(segs * 2);
            t.writeShort(range);
            t.writeShort(selector);
            t.writeShort(segs * 2 - range);
            for (int[] s : segments) t.writeShort(s[1]);
            t.writeShort(0);
            for (int[] s : segments) t.writeShort(s[0]);
            for (int[] s : segments) t.writeShort(s[0] == 0xFFFF ? 1 : s[2] - s[0]);
            for (int i = 0; i < segs; i++) t.writeShort(0);
            return table.toByteArray();
        }

        static byte[] name() throws IOException {
            String[] records = {
                "Copyright (c) 2022, Idrees Hassan (https://github.com/IdreesInc/Monocraft). GNU Unifont glyphs:"
                        + " Copyright © 1998-2025 Roman Czyborra, Paul Hardy, Qianqian Fang, Andrew Miller, Johnnie"
                        + " Weaver, David Corbett, Ælla Chiana Moskopp, Rebecca Bettencourt, Ho-Seok Ee, et al."
                        + " (https://unifoundry.com/unifont/).",
                "Compixel",
                "Regular",
                "Compixel Regular: Monocraft 4.2 and GNU Unifont 17.0.01",
                "Compixel",
                "Version 1.0",
                "Compixel-Regular",
            };
            List<String[]> all = new ArrayList<>();
            for (int i = 0; i < records.length; i++) all.add(new String[] {String.valueOf(i), records[i]});
            all.add(new String[] {"13", "This Font Software is licensed under the SIL Open Font License, Version 1.1."});
            all.add(new String[] {"14", "https://openfontlicense.org"});
            ByteArrayOutputStream strings = new ByteArrayOutputStream(), table = new ByteArrayOutputStream();
            DataOutputStream t = new DataOutputStream(table);
            t.writeShort(0);
            t.writeShort(all.size());
            t.writeShort(6 + all.size() * 12);
            for (String[] r : all) {
                byte[] text = r[1].getBytes(StandardCharsets.UTF_16BE);
                t.writeShort(3);
                t.writeShort(1);
                t.writeShort(0x0409);
                t.writeShort(Integer.parseInt(r[0]));
                t.writeShort(text.length);
                t.writeShort(strings.size());
                strings.write(text);
            }
            strings.writeTo(table);
            return table.toByteArray();
        }

        // The table directory sorted by tag, tables 4-byte aligned, then the whole-font checksum.
        static byte[] assemble(Map<String, byte[]> tables) throws IOException {
            int count = tables.size(), selector = 31 - Integer.numberOfLeadingZeros(count), range = 16 << selector;
            ByteArrayOutputStream font = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(font);
            out.writeInt(0x00010000);
            out.writeShort(count);
            out.writeShort(range);
            out.writeShort(selector);
            out.writeShort(count * 16 - range);
            int offset = 12 + count * 16, head = 0;
            for (var e : tables.entrySet()) {
                if (e.getKey().equals("head")) head = offset;
                out.write(e.getKey().getBytes(StandardCharsets.US_ASCII));
                out.writeInt(checksum(e.getValue()));
                out.writeInt(offset);
                out.writeInt(e.getValue().length);
                offset += (e.getValue().length + 3) / 4 * 4;
            }
            for (byte[] table : tables.values()) {
                out.write(table);
                while (font.size() % 4 != 0) out.write(0);
            }
            byte[] bytes = font.toByteArray();
            ByteBuffer.wrap(bytes).putInt(head + 8, (int) (0xB1B0AFBAL - (checksum(bytes) & 0xFFFFFFFFL)));
            return bytes;
        }

        static int checksum(byte[] data) {
            long sum = 0;
            for (int i = 0; i < data.length; i += 4) {
                long word = 0;
                for (int j = 0; j < 4; j++) word = word << 8 | (i + j < data.length ? data[i + j] & 0xFF : 0);
                sum += word;
            }
            return (int) sum;
        }
    }
}
