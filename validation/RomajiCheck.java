package local.launcherfix;

public final class RomajiCheck {
    static int checks;
    static void check(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
    public static void main(String[] args) throws Exception {
        check(JapaneseRomaji.kana("きゃく").equals("kyaku"), "digraph");
        check(JapaneseRomaji.kana("がっこう").equals("gakkou"), "small tsu");
        check(JapaneseRomaji.kana("マッチャ").equals("matcha"), "ch gemination");
        check(JapaneseRomaji.kana("コーヒー").equals("koohii"), "long vowel");
        check(JapaneseRomaji.kana("しんよう").equals("shin'you"), "syllabic n");
        check(JapaneseRomaji.kana("ファイト").equals("faito"), "loanword");
        check(JapaneseRomaji.kana("ｶﾞｯｺｳ").equals("gakkou"), "half width");
        check(JapaneseRomaji.kana("じゃじゅじょ").equals("jajujo"), "voiced digraph");
        check(JapaneseRomaji.kana("ミュージック").equals("myuujikku"), "mixed loanword");
        check(JapaneseRomaji.kana("ティッシュ").equals("tisshu"), "small tsu sh");
        check(JapaneseRomaji.kana("ABC 123").equals("ABC 123"), "Latin retained");
        Lyrics lyrics=Lyrics.parse("[00:01]今日は晴れです\n[00:03]あなたの声が聞こえる\n[00:06]Hello world\n[00:07]\n[00:08]今日は晴れです");
        long start=System.nanoTime();
        Lyrics result=JapaneseRomaji.convert(lyrics.fromSource("Better Lyrics TTML"));
        check(result.source().equals("Better Lyrics TTML"),"source retained after reading conversion");
        check(result.size()==lyrics.size() && result.indexAt(3000)==1,"timing intact");
        check(result.at(0).equals(lyrics.at(0)),"original unchanged");
        check(result.romajiAt(0).equals("kyou wa hare desu"),"kanji and particle");
        check(result.romajiAt(1).equals("anata no koe ga kikoeru"),"sentence reading");
        check(result.romajiAt(2).isEmpty(),"no redundant Latin line");
        check(result.romajiAt(3).isEmpty(),"blank interlude");
        check(result.romajiAt(4).equals(result.romajiAt(0)),"repeat line");
        check(result.romajiAt(-1).isEmpty() && result.romajiAt(5).isEmpty(),"bounds");
        check(JapaneseRomaji.convert(Lyrics.parse("[00:01]Hello" )).romajiAt(0).isEmpty(),"non Japanese track");
        Thread.currentThread().interrupt();
        try { JapaneseRomaji.convert(lyrics); throw new AssertionError("cancel not honored"); }
        catch(java.io.InterruptedIOException expected) { checks++; }
        finally { Thread.interrupted(); }
        System.out.println("ROMAJI_CHECK_OK cases="+checks+" elapsed_ms="+(System.nanoTime()-start)/1000000);
    }
}
