package local.launcherfix;

import android.graphics.Paint;
import android.graphics.Typeface;

/** Verifies per-token Japanese ruby alignment, sokuon gemination, wrapping, and karaoke prefix geometry. */
public final class RubyLayoutCheck {
    static int count;
    static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        count++;
    }

    private static String joinSurfaces(Lyrics.Ruby[] rubies) {
        StringBuilder sb = new StringBuilder();
        for (Lyrics.Ruby r : rubies) sb.append(r.surface);
        return sb.toString();
    }

    private static Lyrics.Ruby findSurface(Lyrics.Ruby[] rubies, String surface) {
        if (rubies != null) for (Lyrics.Ruby r : rubies) if (surface.equals(r.surface)) return r;
        return null;
    }

    public static void main(String[] args) throws Exception {
        String sample = String.join("\n",
            "[00:01.00]言えないことも全部したいよ",
            "[00:04.00]可愛いその表情で",
            "[00:07.00]僕のことを殴って",
            "[00:10.00]壊して",
            "[00:13.00]プロポーズ - propose",
            "[00:16.00]私の志"
        );
        Lyrics parsed = Lyrics.parse(sample).fromSource("LRCLIB").withCredit("https://example.com", "Author", "https://example.com/u");
        Lyrics converted = JapaneseRomaji.convert(parsed);

        // 1. Surface conservation & attribution preservation across all lines.
        check(converted.source().equals("LRCLIB") && converted.contributor().equals("Author"), "metadata preserved");
        for (int i = 0; i < converted.size(); i++) {
            Lyrics.Ruby[] rubies = converted.rubiesAt(i);
            check(rubies != null && rubies.length > 0, "rubies generated for line " + i);
            check(joinSurfaces(rubies).equals(converted.at(i)), "exact surface conservation line " + i);
        }

        // 2. Reference song token-level ruby accuracy (matching Mochy Music reference).
        Lyrics.Ruby[] line1 = converted.rubiesAt(1); // 可愛いその表情で
        check(findSurface(line1, "可愛い") != null && "kawaii".equals(findSurface(line1, "可愛い").romaji), "kawaii ruby");
        check(findSurface(line1, "その") != null && "sono".equals(findSurface(line1, "その").romaji), "sono ruby");
        check(findSurface(line1, "表情") != null && "hyoujou".equals(findSurface(line1, "表情").romaji), "hyoujou ruby");
        check(findSurface(line1, "で") != null && "de".equals(findSurface(line1, "で").romaji), "particle de ruby");

        // 3. Cross-token sokuon gemination (殴っ + て -> nagut + te) & particle を -> o.
        Lyrics.Ruby[] line2 = converted.rubiesAt(2); // 僕のことを殴って
        check(findSurface(line2, "を") != null && "o".equals(findSurface(line2, "を").romaji), "particle wo -> o ruby");
        check(findSurface(line2, "殴っ") != null && "nagut".equals(findSurface(line2, "殴っ").romaji), "sokuon stem nagut");
        check(findSurface(line2, "て") != null && "te".equals(findSurface(line2, "て").romaji), "attached te");

        // 4. Mixed Japanese + Latin line omits redundant Latin ruby under English text.
        Lyrics.Ruby[] mixed = converted.rubiesAt(4); // プロポーズ - propose
        boolean katakanaHasRuby = false, latinOmitted = true;
        for (Lyrics.Ruby r : mixed) {
            if (r.surface.contains("プロポーズ") && "puropoozu".equals(r.romaji)) katakanaHasRuby = true;
            if (r.surface.contains("propose") && !r.romaji.isEmpty()) latinOmitted = false;
        }
        check(katakanaHasRuby && latinOmitted, "mixed Japanese/Latin ruby policy");

        // 5. Layout engine: non-overlapping romaji cells even when romaji is wider than single kanji (私の志).
        Paint jpPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        jpPaint.setTypeface(Typeface.DEFAULT_BOLD);
        jpPaint.setTextSize(48f);
        Paint roPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        roPaint.setTypeface(Typeface.DEFAULT);
        roPaint.setTextSize(20f);

        RubyLyricsView.LineBlock narrowKanji = RubyLyricsView.measureBlock(
            converted.at(5), converted.rubiesAt(5), jpPaint, roPaint, 10f, 5f, 14f, 640, 4, null);
        check(narrowKanji.rows.length == 1, "single row wide width");
        RubyLyricsView.Cell[] cells = narrowKanji.rows[0].cells;
        for (int j = 0; j + 1 < cells.length; j++) {
            float rightEdge = cells[j].x + cells[j].cellW;
            check(rightEdge <= cells[j + 1].x + 0.05f, "no horizontal cell collision");
            if (!cells[j].romaji.isEmpty()) {
                check(cells[j].cellW >= cells[j].roW + 9.9f, "romaji min gap respected");
            }
        }

        // 6. Constrained width wrapping preserves codePoint sequence & stays within maxWidth.
        RubyLyricsView.LineBlock wrapped = RubyLyricsView.measureBlock(
            converted.at(0), converted.rubiesAt(0), jpPaint, roPaint, 10f, 5f, 14f, 360, 4, null);
        check(wrapped.rows.length >= 2, "long Japanese line wraps cleanly");
        int expectedCp = 0;
        for (RubyLyricsView.VisualRow row : wrapped.rows) {
            check(row.hasRomaji && row.roBaseline > row.jpBaseline, "wrapped row retains ruby baseline");
            for (RubyLyricsView.Cell c : row.cells) {
                check(c.x + c.cellW <= 360.5f, "wrapped cell within maxWidth");
                check(c.cpStart == expectedCp, "contiguous codePoint start");
                expectedCp = c.cpEnd;
            }
        }
        check(expectedCp == converted.at(0).codePointCount(0, converted.at(0).length()), "all codePoints accounted for");

        // 7. Karaoke prefix width monotonic progression.
        float p0 = RubyLyricsView.prefixWidth("言えない", 0.0, jpPaint);
        float pHalf = RubyLyricsView.prefixWidth("言えない", 1.5, jpPaint);
        float pFull = RubyLyricsView.prefixWidth("言えない", 4.0, jpPaint);
        check(p0 == 0f && pHalf > 0f && pFull > pHalf && Math.abs(pFull - jpPaint.measureText("言えない")) < 0.5f,
            "karaoke prefix width monotonic and exact");

        // 8. Pure English line has zero romaji overhead height.
        Lyrics eng = JapaneseRomaji.convert(Lyrics.parse("[00:01.00]Hello liquid glass world"));
        check(eng.rubiesAt(0) == null, "pure English skips rubies");
        RubyLyricsView.LineBlock engBlock = RubyLyricsView.measureBlock(
            eng.at(0), eng.rubiesAt(0), jpPaint, roPaint, 10f, 5f, 14f, 640, 4, null);
        check(engBlock.rows.length == 1 && !engBlock.rows[0].hasRomaji, "English row compact height");

        // 9. Mochy animation curves: staggered cascade order, monotonic non-overshooting bounds, C1 word lift, VSync gate.
        check(RubyLyricsView.cascadeProgress(0f, -1) == 0f && RubyLyricsView.cascadeProgress(0f, 0) == 0f
            && RubyLyricsView.cascadeProgress(0f, 3) == 0f, "cascade starts at 0");
        check(RubyLyricsView.cascadeProgress(1f, -1) == 1f && RubyLyricsView.cascadeProgress(1f, 0) == 1f
            && RubyLyricsView.cascadeProgress(1f, 4) == 1f, "cascade settles at 1");
        float cMinus1 = RubyLyricsView.cascadeProgress(0.35f, -1);
        float c0 = RubyLyricsView.cascadeProgress(0.35f, 0);
        float c1 = RubyLyricsView.cascadeProgress(0.35f, 1);
        float c2 = RubyLyricsView.cascadeProgress(0.35f, 2);
        check(cMinus1 > c0 && c0 > c1 && c1 > c2, "staggered cascade ripple order");
        float prev = 0f;
        for (int step = 0; step <= 20; step++) {
            float cur = RubyLyricsView.cascadeProgress(step / 20f, 1);
            check(cur >= prev && cur <= 1.0001f, "cascade strictly monotonic without overshoot");
            prev = cur;
        }
        check(RubyLyricsView.wordLiftFraction(Double.NaN, 0, 2) == 0f, "untimed lift 0");
        check(RubyLyricsView.wordLiftFraction(0.0, 0, 2) == 0f, "unsung lift 0");
        float peakLift = RubyLyricsView.wordLiftFraction(1.1, 0, 2);
        float settledLift = RubyLyricsView.wordLiftFraction(2.0, 0, 2);
        check(peakLift > settledLift && Math.abs(settledLift - 0.78f) < 0.001f, "word lift peaks then settles smoothly");
        String timedYaml = "version: '1.0'\nmetadata: {title: Song, artist: Artist}\nlines:\n"
            + "- text: 'Hello world'\n  start_ms: 1000\n  end_ms: 5000\n  words:\n"
            + "  - {text: 'Hello ', start_ms: 1000, end_ms: 2000}\n"
            + "  - {text: world, start_ms: 3500, end_ms: 4500}\n";
        Lyrics timed = TimedLyrics.parse(timedYaml, "Song", "Artist");
        check(timed.isActivelySweeping(0, 1500), "60fps VSync active mid-word");
        check(!timed.isActivelySweeping(0, 2700), "60fps VSync sleeps in long gap");
        check(!timed.isActivelySweeping(0, 4800), "60fps VSync sleeps after last word");
        check(!eng.isActivelySweeping(0, 1500), "60fps VSync disabled on line-only lyrics");

        System.out.println("RUBY_LAYOUT_CHECK_OK cases=" + count);
    }
}
