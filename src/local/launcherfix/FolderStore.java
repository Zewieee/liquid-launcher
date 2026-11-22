package local.launcherfix;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.json.*;

/** Folder records are independent of the launcher's shortcuts, favourites and saved order. */
public final class FolderStore {
    private static final String FILE="app_folders.json";
    private static final int VERSION=1,MAX_BYTES=262144,MAX_FOLDERS=128,MAX_MEMBERS=512;
    private static final Pattern PACKAGE=Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private FolderStore() {}

    public static final class Folder {
        public final String id,name,anchorComponentKey;
        public final List<String> packages;
        private Folder(String id,String name,Collection<String> packages,String anchor) {
            this.id=id;this.name=name;this.packages=Collections.unmodifiableList(new ArrayList<>(packages));
            this.anchorComponentKey=anchor;
        }
    }
    public static final class Snapshot {
        public final List<Folder> folders;
        private Snapshot(Collection<Folder> folders) {this.folders=Collections.unmodifiableList(new ArrayList<>(folders));}
        public static Snapshot empty() {return new Snapshot(Collections.emptyList());}
        public Folder find(String id) {for(Folder folder:folders)if(folder.id.equals(id))return folder;return null;}
        public Map<String,String> membership() {
            LinkedHashMap<String,String> members=new LinkedHashMap<>();
            for(Folder folder:folders)for(String pkg:folder.packages)members.put(pkg,folder.id);
            return Collections.unmodifiableMap(members);
        }
    }
    public static String componentKey(Context context,String id) {return context.getPackageName()+"/.folder."+id;}
    private static AtomicFile file(Context context) {return new AtomicFile(new File(context.getFilesDir(),FILE));}

    public static synchronized Snapshot read(Context context) throws IOException {
        AtomicFile atomic=file(context);File base=atomic.getBaseFile();File backup=new File(base.getPath()+".bak");
        if(base.length()>MAX_BYTES||backup.length()>MAX_BYTES)throw new IOException("Folder data is too large");
        byte[] bytes;
        try {bytes=atomic.readFully();}
        catch(FileNotFoundException error) {if(!base.exists()&&!backup.exists())return Snapshot.empty();throw error;}
        if(bytes.length>MAX_BYTES)throw new IOException("Folder data is too large");
        try {
            String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JSONTokener tokener=new JSONTokener(json);Object value=tokener.nextValue();
            if(!(value instanceof JSONObject)||tokener.nextClean()!=0)throw new IOException("Invalid folder data");
            JSONObject root=(JSONObject)value;
            if(!Integer.valueOf(VERSION).equals(root.get("version")))throw new IOException("Unsupported folder data version");
            JSONArray array=root.getJSONArray("folders");
            if(array.length()>MAX_FOLDERS)throw new IOException("Too many folders");
            ArrayList<Folder> folders=new ArrayList<>();HashSet<String> ids=new HashSet<>(),members=new HashSet<>();
            for(int i=0;i<array.length();i++) {
                JSONObject object=array.getJSONObject(i);String id=string(object,"id"),name=string(object,"name");
                if(!UUID.fromString(id).toString().equals(id)||!ids.add(id))throw new IOException("Invalid folder ID");
                if(!normalizeName(name).equals(name))throw new IOException("Invalid folder name");
                JSONArray packages=object.getJSONArray("packages");
                if(packages.length()>MAX_MEMBERS)throw new IOException("Too many folder members");
                ArrayList<String> names=new ArrayList<>();
                for(int p=0;p<packages.length();p++) {
                    Object packageValue=packages.get(p);
                    if(!(packageValue instanceof String))throw new IOException("Invalid package name");
                    String pkg=(String)packageValue;validatePackage(pkg);
                    if(!members.add(pkg))throw new IOException("Duplicate folder membership");
                    names.add(pkg);
                }
                String anchor=null;
                if(object.has("anchor")&&!object.isNull("anchor"))anchor=validateAnchor(string(object,"anchor"));
                folders.add(new Folder(id,name,names,anchor));
            }
            return new Snapshot(folders);
        } catch(JSONException|IllegalArgumentException error) {throw new IOException("Cannot read folder data",error);}
    }

    public static synchronized Folder create(Context context,String name,Collection<String> packages,String anchor) throws IOException {
        Snapshot before=read(context);
        if(before.folders.size()>=MAX_FOLDERS)throw new IOException("Too many folders");
        List<String> members=normalizePackages(context,packages);
        Folder folder=new Folder(UUID.randomUUID().toString(),normalizeName(name),members,validateAnchor(anchor));
        ArrayList<Folder> next=removeMembers(before.folders,new HashSet<>(members));next.add(folder);
        write(context,new Snapshot(next));return folder;
    }
    public static synchronized void rename(Context context,String id,String name) throws IOException {
        Snapshot before=read(context);Folder old=required(before,id);String normalized=normalizeName(name);
        if(old.name.equals(normalized))return;
        ArrayList<Folder> next=new ArrayList<>();
        for(Folder folder:before.folders)next.add(folder.id.equals(id)?new Folder(id,normalized,folder.packages,folder.anchorComponentKey):folder);
        write(context,new Snapshot(next));
    }
    public static synchronized void updateMembers(Context context,String id,Collection<String> packages) throws IOException {
        Snapshot before=read(context);Folder old=required(before,id);List<String> members=normalizePackages(context,packages);
        if(old.packages.equals(members))return;
        HashSet<String> moved=new HashSet<>(members);ArrayList<Folder> next=new ArrayList<>();
        for(Folder folder:before.folders) {
            if(folder.id.equals(id))next.add(new Folder(id,folder.name,members,folder.anchorComponentKey));
            else next.add(without(folder,moved));
        }
        write(context,new Snapshot(next));
    }
    public static synchronized void delete(Context context,String id) throws IOException {
        Snapshot before=read(context);required(before,id);ArrayList<Folder> next=new ArrayList<>();
        for(Folder folder:before.folders)if(!folder.id.equals(id))next.add(folder);
        write(context,new Snapshot(next));
    }
    private static Folder required(Snapshot snapshot,String id) throws IOException {
        Folder folder=snapshot.find(id);if(folder==null)throw new IOException("Folder no longer exists");return folder;
    }
    private static ArrayList<Folder> removeMembers(List<Folder> folders,Set<String> moved) {
        ArrayList<Folder> next=new ArrayList<>();for(Folder folder:folders)next.add(without(folder,moved));return next;
    }
    private static Folder without(Folder folder,Set<String> removed) {
        ArrayList<String> members=new ArrayList<>();for(String pkg:folder.packages)if(!removed.contains(pkg))members.add(pkg);
        return members.equals(folder.packages)?folder:new Folder(folder.id,folder.name,members,folder.anchorComponentKey);
    }
    private static String normalizeName(String value) throws IOException {
        if(value==null)throw new IOException("Enter a folder name");String name=value.trim();
        if(name.isEmpty()||name.codePointCount(0,name.length())>40)throw new IOException("Folder names must contain 1 to 40 characters");
        for(int i=0;i<name.length();i++)if(Character.isISOControl(name.charAt(i)))throw new IOException("Invalid folder name");
        return name;
    }
    private static void validatePackage(String pkg) throws IOException {
        if(pkg==null||pkg.length()>255||!PACKAGE.matcher(pkg).matches())throw new IOException("Invalid package name");
    }
    private static List<String> normalizePackages(Context context,Collection<String> packages) throws IOException {
        if(packages==null)throw new IOException("Missing folder members");LinkedHashSet<String> unique=new LinkedHashSet<>();
        for(String pkg:packages) {validatePackage(pkg);if(context.getPackageName().equals(pkg))throw new IOException("A folder cannot contain the launcher");unique.add(pkg);}
        if(unique.size()>MAX_MEMBERS)throw new IOException("Too many folder members");return new ArrayList<>(unique);
    }
    private static String validateAnchor(String anchor) throws IOException {
        if(anchor==null||anchor.isEmpty())return null;
        int slash=anchor.indexOf('/');if(slash<=0||slash==anchor.length()-1||anchor.length()>1024)throw new IOException("Invalid folder anchor");
        validatePackage(anchor.substring(0,slash));
        for(int i=slash+1;i<anchor.length();i++)if(Character.isWhitespace(anchor.charAt(i))||Character.isISOControl(anchor.charAt(i)))throw new IOException("Invalid folder anchor");
        return anchor;
    }
    private static String string(JSONObject object,String key) throws JSONException,IOException {
        Object value=object.get(key);if(!(value instanceof String))throw new IOException("Invalid folder field: "+key);return (String)value;
    }
    private static void write(Context context,Snapshot snapshot) throws IOException {
        byte[] bytes;
        try {
            JSONObject root=new JSONObject();root.put("version",VERSION);JSONArray folders=new JSONArray();
            for(Folder folder:snapshot.folders) {
                JSONObject object=new JSONObject();object.put("id",folder.id);object.put("name",folder.name);
                object.put("packages",new JSONArray(folder.packages));if(folder.anchorComponentKey!=null)object.put("anchor",folder.anchorComponentKey);
                folders.put(object);
            }
            root.put("folders",folders);bytes=root.toString().getBytes(StandardCharsets.UTF_8);
        } catch(JSONException error) {throw new IOException("Cannot save folders",error);}
        if(bytes.length>MAX_BYTES)throw new IOException("Folder data is too large");
        AtomicFile atomic=file(context);FileOutputStream stream=null;
        try {stream=atomic.startWrite();stream.write(bytes);atomic.finishWrite(stream);}
        catch(IOException|RuntimeException error) {if(stream!=null)atomic.failWrite(stream);throw new IOException("Cannot save folders",error);}
    }
}
