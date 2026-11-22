package local.launcherfix;

import java.net.URI;
import java.net.URLDecoder;

/** Interpret media IDs only for known YouTube players; never search arbitrary metadata text. */
public final class YouTubeTrack {
    private YouTubeTrack() {}
    public static boolean valid(String id) { return id!=null && id.matches("[A-Za-z0-9_-]{11}"); }
    public static boolean source(String pkg) {
        return "org.smarttube.beta".equals(pkg) || "org.smarttube.stable".equals(pkg)
            || "com.liskovsoft.smarttubetv2.tv".equals(pkg) || "com.google.android.youtube.tv".equals(pkg)
            || "com.google.android.youtube".equals(pkg) || "com.google.android.apps.youtube.music".equals(pkg)
            || "app.revanced.android.apps.youtube.music".equals(pkg)
            || "com.music.bitchord.tv".equals(pkg);
    }
    public static String extract(String pkg,String mediaId,String mediaUri) {
        if(!source(pkg))return "";
        String first=parse(mediaId),second=parse(mediaUri);
        if(!first.isEmpty() && !second.isEmpty() && !first.equals(second))return "";
        return first.isEmpty() ? second : first;
    }
    private static String parse(String value) {
        if(value==null || value.length()>512)return "";
        if(valid(value))return value;
        for(String prefix:new String[]{"youtube:video:","vnd.youtube:"})
            if(value.startsWith(prefix) && valid(value.substring(prefix.length())))return value.substring(prefix.length());
        try {
            URI uri=new URI(value);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo()!=null || uri.getPort()!=-1)return "";
            String host=uri.getHost(),path=uri.getPath();String id="";
            if("youtu.be".equalsIgnoreCase(host))id=path.startsWith("/") ? path.substring(1) : "";
            else if("youtube.com".equalsIgnoreCase(host) || "www.youtube.com".equalsIgnoreCase(host)
                    || "music.youtube.com".equalsIgnoreCase(host) || "m.youtube.com".equalsIgnoreCase(host)) {
                if("/watch".equals(path)) {
                    String query=uri.getRawQuery();if(query==null)return "";
                    boolean found=false;
                    for(String part:query.split("&")) {
                        String[] pair=part.split("=",2);
                        if("v".equals(URLDecoder.decode(pair[0],"UTF-8"))) {
                            if(found || pair.length!=2)return "";found=true;id=URLDecoder.decode(pair[1],"UTF-8");
                        }
                    }
                } else for(String prefix:new String[]{"/embed/","/shorts/","/live/"})
                    if(path.startsWith(prefix))id=path.substring(prefix.length());
            }
            return valid(id) ? id : "";
        }catch(Exception invalid){return "";}
    }
}
