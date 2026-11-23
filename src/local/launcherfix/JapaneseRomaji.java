package local.launcherfix;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;
import java.io.InterruptedIOException;
import java.lang.ref.SoftReference;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/** Softly cached dictionary reused across songs and evicted on UI-hidden/memory trim. */
final class JapaneseRomaji {
    private static final Object TOKENIZER_LOCK = new Object();
    private static volatile SoftReference<Tokenizer> sCachedTokenizer;
    private static final Map<String,String> KANA = new HashMap<>();
    static {
        String letters="ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺほぼぽまみむめもゃやゅゆょよらりるれろゎわゐゑをんゔゕゖ";
        String[] values=("a a i i u u e e o o ka ga ki gi ku gu ke ge ko go sa za shi ji su zu se ze so zo ta da chi ji tsu tsu zu te de to do na ni nu ne no ha ba pa hi bi pi fu bu pu he be pe ho bo po ma mi mu me mo ya ya yu yu yo yo ra ri ru re ro wa wa wi we o n vu ka ke").split(" ");
        if (letters.length() != values.length) throw new AssertionError("Kana table length");
        for (int i=0; i<letters.length(); i++) KANA.put(letters.substring(i,i+1),values[i]);
        String[] stems={"き","ぎ","し","じ","ち","ぢ","に","ひ","び","ぴ","み","り"};
        String[] latin={"ky","gy","sh","j","ch","j","ny","hy","by","py","my","ry"};
        for (int i=0; i<stems.length; i++) for (int j=0; j<3; j++)
            KANA.put(stems[i]+"ゃゅょ".charAt(j),latin[i]+"auo".charAt(j));
        String[] extra={"ふぁ:fa","ふぃ:fi","ふぇ:fe","ふぉ:fo","ふゅ:fyu","ゔぁ:va","ゔぃ:vi","ゔぇ:ve","ゔぉ:vo","ゔゅ:vyu",
            "てぃ:ti","でぃ:di","とぅ:tu","どぅ:du","ちぇ:che","しぇ:she","じぇ:je","いぇ:ye","うぃ:wi","うぇ:we","うぉ:wo",
            "くぁ:kwa","くぃ:kwi","くぇ:kwe","くぉ:kwo","ぐぁ:gwa","つぁ:tsa","つぃ:tsi","つぇ:tse","つぉ:tso","てゅ:tyu","でゅ:dyu"};
        for (String pair:extra) { String[] parts=pair.split(":"); KANA.put(parts[0],parts[1]); }
    }
    static Tokenizer obtainTokenizer() {
        synchronized (TOKENIZER_LOCK) {
            Tokenizer t = sCachedTokenizer == null ? null : sCachedTokenizer.get();
            if (t == null) {
                t = new Tokenizer();
                sCachedTokenizer = new SoftReference<>(t);
            }
            return t;
        }
    }
    static void trimMemory(int level) {
        if (level >= 20 || level == 10 || level == 15) {
            synchronized (TOKENIZER_LOCK) {
                sCachedTokenizer = null;
            }
        }
    }
    static Lyrics convert(Lyrics lyrics) throws InterruptedIOException {
        cancelled();
        boolean japanese=false;
        for (int i=0; i<lyrics.size(); i++) if (hasKana(lyrics.at(i))) { japanese=true; break; }
        if (!japanese) return lyrics;
        Tokenizer tokenizer=obtainTokenizer();
        Map<String,Reading> repeated=new HashMap<>();
        Lyrics.Word[][] mapped=new Lyrics.Word[lyrics.size()][];
        Lyrics.Ruby[][] rubies=new Lyrics.Ruby[lyrics.size()][];
        String[] readings=new String[lyrics.size()];
        for (int i=0; i<readings.length; i++) {
            cancelled(); String original=lyrics.at(i);
            Reading reading=repeated.get(original);
            if (reading == null) { reading=line(tokenizer,original,false); repeated.put(original,reading); }
            readings[i]=reading.text; mapped[i]=reading.map(lyrics.wordsAt(i)); rubies[i]=reading.rubies;
        }
        return lyrics.withRomaji(readings,mapped,rubies);
    }
    private static void cancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Romaji conversion cancelled");
    }
    private static boolean hasKana(String value) {
        for (int i=0; i<value.length(); i++) {
            char c=value.charAt(i);
            if (c >= 0x3041 && c <= 0x3096 || c >= 0x30a1 && c <= 0x30fa || c >= 0xff66 && c <= 0xff9d) return true;
        }
        return false;
    }
    private static boolean hasHan(String value) {
        for (int i=0; i<value.length(); i++) if (Character.UnicodeScript.of(value.charAt(i)) == Character.UnicodeScript.HAN) return true;
        return false;
    }
    private static boolean endsWithSokuon(String value) {
        if (value == null || value.isEmpty()) return false;
        char c = value.charAt(value.length() - 1);
        return c == 'っ' || c == 'ッ' || c == '\uff6f';
    }
    private static final class Reading {
        final String text; final int[] boundaries; final Lyrics.Ruby[] rubies;
        Reading(String text,int[] boundaries,Lyrics.Ruby[] rubies) {
            this.text=text; this.boundaries=boundaries; this.rubies=rubies;
        }
        Lyrics.Word[] map(Lyrics.Word[] words) {
            if (words==null || text.isEmpty() || boundaries==null) return null;
            Lyrics.Word[] mapped=new Lyrics.Word[words.length];
            for (int i=0;i<words.length;i++) {
                Lyrics.Word word=words[i]; int from=boundaries[word.from],to=boundaries[word.to];
                if (from<0 || to<=from || to>text.length()) return null;
                mapped[i]=new Lyrics.Word(from,to,word.start,word.end);
            }
            return mapped;
        }
    }
    static String alias(Tokenizer tokenizer,String original) { return line(tokenizer,original,true).text; }
    private static Reading line(Tokenizer tokenizer,String original,boolean strictAllKanji) {
        if (!hasKana(original) && !hasHan(original)) return new Reading("",null,null);
        java.util.List<Token> tokens;
        synchronized (tokenizer) {
            tokens=tokenizer.tokenize(original);
        }
        Lyrics.Ruby[] rubies=buildRubies(tokens,original);
        int[] boundaries=new int[original.length()+1]; java.util.Arrays.fill(boundaries,-1);
        StringBuilder result=new StringBuilder();
        boolean skippedKanji=false;
        for (Token token:tokens) {
            String surface=token.getSurface(), reading=token.getReading();
            if (surface.indexOf('\n') >= 0) { result.append('\n'); continue; }
            int originalStart=token.getPosition(),originalEnd=originalStart+surface.length();
            if (reading == null || reading.equals("*")) {
                if (hasHan(surface)) {
                    if (strictAllKanji) return new Reading("",null,rubies);
                    skippedKanji=true;
                    if (originalStart>=0 && originalStart<=original.length()) boundaries[originalStart]=result.length();
                    if (originalEnd>=0 && originalEnd<=original.length()) boundaries[originalEnd]=result.length();
                    continue;
                }
                reading=surface;
            }
            if (token.getPartOfSpeechLevel1().equals("助詞")) {
                if (surface.equals("は")) reading="ワ";
                else if (surface.equals("へ")) reading="エ";
                else if (surface.equals("を")) reading="オ";
            }
            String word=kana(reading).trim();
            if (word.isEmpty()) { boundaries[originalStart]=result.length(); boundaries[originalEnd]=result.length(); continue; }
            boolean punctuation=token.getPartOfSpeechLevel1().equals("記号");
            boolean attach=token.getPartOfSpeechLevel1().equals("助動詞") || token.getPartOfSpeechLevel2().equals("接尾")
                || token.getPartOfSpeechLevel2().equals("接続助詞") || token.getPartOfSpeechLevel2().equals("非自立");
            if (token.getConjugationType().equals("特殊・デス") || token.getConjugationType().equals("特殊・ダ")) attach=false;
            if (result.length()>0 && !punctuation && !attach && result.charAt(result.length()-1)!='\n') result.append(' ');
            boundaries[originalStart]=result.length();
            result.append(word); boundaries[originalEnd]=result.length();
        }
        return new Reading(result.toString().trim(),skippedKanji ? null : boundaries,rubies);
    }
    private static Lyrics.Ruby[] buildRubies(java.util.List<Token> tokens,String original) {
        int n=tokens.size();
        String[] words=new String[n];
        boolean[] sokuon=new boolean[n];
        for (int i=0;i<n;i++) {
            Token token=tokens.get(i);
            String surface=token.getSurface(), reading=token.getReading();
            if (surface.indexOf('\n') >= 0 || (!hasKana(surface) && !hasHan(surface))) {
                words[i]="";
                continue;
            }
            if (reading == null || reading.equals("*")) {
                if (hasHan(surface)) { words[i]=""; continue; }
                reading=surface;
            } else if ("助詞".equals(token.getPartOfSpeechLevel1())) {
                if ("は".equals(surface)) reading="ワ";
                else if ("へ".equals(surface)) reading="エ";
                else if ("を".equals(surface)) reading="オ";
            }
            words[i]=kana(reading).trim();
            sokuon[i]=endsWithSokuon(reading) || endsWithSokuon(surface);
        }
        for (int i=0;i+1<n;i++) {
            if (!sokuon[i]) continue;
            String next=words[i+1];
            if (next.isEmpty()) continue;
            char c=next.charAt(0);
            if ("aeioun".indexOf(c) < 0) words[i]=words[i]+(next.startsWith("ch") ? 't' : c);
        }
        java.util.ArrayList<Lyrics.Ruby> list=new java.util.ArrayList<>(n);
        boolean anyRomaji=false;
        int cursor=0;
        for (int i=0;i<n;i++) {
            Token token=tokens.get(i);
            String surface=token.getSurface();
            int start=Math.max(cursor,Math.min(original.length(),token.getPosition()));
            int end=Math.max(start,Math.min(original.length(),start+surface.length()));
            if (start>cursor) appendRuby(list,cursor,start,original.substring(cursor,start),"");
            String romaji=words[i];
            if (!romaji.isEmpty()) anyRomaji=true;
            appendRuby(list,start,end,original.substring(start,end),romaji);
            cursor=end;
        }
        if (cursor<original.length()) appendRuby(list,cursor,original.length(),original.substring(cursor),"");
        return anyRomaji ? list.toArray(new Lyrics.Ruby[0]) : null;
    }
    private static void appendRuby(java.util.ArrayList<Lyrics.Ruby> list,int from,int to,String text,String romaji) {
        if (from>=to || text.isEmpty()) return;
        int nl=text.indexOf('\n');
        if (nl<0) { list.add(new Lyrics.Ruby(from,to,text,romaji)); return; }
        int pos=0;
        while (pos<text.length()) {
            int next=text.indexOf('\n',pos);
            if (next<0) {
                list.add(new Lyrics.Ruby(from+pos,to,text.substring(pos),pos==0 ? romaji : ""));
                break;
            }
            if (next>pos) list.add(new Lyrics.Ruby(from+pos,from+next,text.substring(pos,next),pos==0 ? romaji : ""));
            list.add(new Lyrics.Ruby(from+next,from+next+1,"\n",""));
            pos=next+1;
        }
    }
    static String kana(String text) {
        text=Normalizer.normalize(text,Normalizer.Form.NFKC);
        StringBuilder normalized=new StringBuilder(text.length());
        for (int i=0; i<text.length(); i++) {
            char c=text.charAt(i); normalized.append(c >= 0x30a1 && c <= 0x30f6 ? (char)(c-0x60) : c);
        }
        text=normalized.toString(); StringBuilder result=new StringBuilder(); boolean doubled=false;
        for (int i=0; i<text.length(); i++) {
            char c=text.charAt(i);
            if (c=='っ') { doubled=true; continue; }
            if (c=='ー') {
                if (result.length()>0 && "aeiou".indexOf(result.charAt(result.length()-1))>=0) result.append(result.charAt(result.length()-1));
                continue;
            }
            String word=null;
            if (i+1<text.length()) word=KANA.get(text.substring(i,i+2));
            if (word != null) i++; else word=KANA.get(text.substring(i,i+1));
            if (word == null) word=String.valueOf(c);
            if (doubled && word.length()>0 && "aeioun".indexOf(word.charAt(0))<0) result.append(word.startsWith("ch") ? 't' : word.charAt(0));
            doubled=false; result.append(word);
            if (c=='ん' && i+1<text.length() && "あいうえおやゆよ".indexOf(text.charAt(i+1))>=0) result.append('\'');
        }
        return result.toString();
    }
}
