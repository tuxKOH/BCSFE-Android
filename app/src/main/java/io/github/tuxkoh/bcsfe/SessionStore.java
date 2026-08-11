package io.github.tuxkoh.bcsfe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class SessionStore {
    static final int MAX_HISTORY_STEPS=50;
    static final class Session {
        final String id;
        final byte[] save;
        final String name;
        final String password;
        final long modifiedAt;
        Session(String id,byte[] save,String name,String password,long modifiedAt){this.id=id;this.save=save;this.name=name;this.password=password;this.modifiedAt=modifiedAt;}
    }
    static final class HistoryEntry {
        final int index;
        final String label;
        final long modifiedAt;
        final boolean initial;
        HistoryEntry(int index,String label,long modifiedAt,boolean initial){this.index=index;this.label=label;this.modifiedAt=modifiedAt;this.initial=initial;}
    }
    static final class History {
        final List<HistoryEntry> entries;
        final int currentIndex;
        History(List<HistoryEntry> entries,int currentIndex){this.entries=entries;this.currentIndex=currentIndex;}
    }
    private static final class HistoryRecord {
        final String file;
        final String label;
        final long modifiedAt;
        HistoryRecord(String file,String label,long modifiedAt){this.file=file;this.label=label;this.modifiedAt=modifiedAt;}
    }

    private final File directory;
    private final File sessionsDirectory;
    private final File currentFile;

    SessionStore(File directory) {
        this.directory=directory;this.sessionsDirectory=new File(directory,"save-sessions");this.currentFile=new File(directory,"save-sessions.current");
    }

    synchronized Session create(byte[] data,String name,String password)throws IOException{
        String id=UUID.randomUUID().toString();save(id,data,name,password);setCurrent(id);return load(id);
    }
    synchronized void save(byte[] data,String name)throws IOException{save(data,name,null);}
    synchronized void save(byte[] data,String name,String password)throws IOException{
        migrateLegacy();String id=currentId();if(id==null){create(data,name,password);return;}save(id,data,name,password);
    }
    synchronized void save(String id,byte[] data,String name,String password)throws IOException{save(id,data,name,password,null);}
    synchronized void save(String id,byte[] data,String name,String password,String historyLabel)throws IOException{
        requireId(id);File folder=sessionFolder(id);if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cannot create session directory");
        File saveFile=new File(folder,"save.bin"),passwordFile=new File(folder,"password.txt");byte[] previous=saveFile.isFile()?Files.readAllBytes(saveFile.toPath()):null;String previousPassword=readText(passwordFile,null);
        ensureInitial(folder,previous==null?data:previous,previous==null?password:previousPassword);
        if(previous!=null&&!Arrays.equals(previous,data))appendHistory(folder,data,password,historyLabel);
        replaceAtomically(saveFile,data);replaceAtomically(new File(folder,"name.txt"),safeName(name).getBytes(StandardCharsets.UTF_8));
        writeOptionalText(passwordFile,password);
        replaceAtomically(new File(folder,"modified.txt"),Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII));
    }
    synchronized Session load()throws IOException{migrateLegacy();String id=currentId();return id==null?null:load(id);}
    synchronized Session load(String id)throws IOException{
        requireId(id);File folder=sessionFolder(id),saveFile=new File(folder,"save.bin");if(!saveFile.isFile())return null;
        String name=readText(new File(folder,"name.txt"),"SAVE_DATA"),password=readText(new File(folder,"password.txt"),null),modified=readText(new File(folder,"modified.txt"),"0");
        long time;try{time=Long.parseLong(modified);}catch(NumberFormatException ignored){time=saveFile.lastModified();}
        return new Session(id,Files.readAllBytes(saveFile.toPath()),safeName(name),password,time);
    }
    synchronized List<Session> list()throws IOException{
        migrateLegacy();List<Session> sessions=new ArrayList<>();File[] folders=sessionsDirectory.listFiles(File::isDirectory);if(folders!=null)for(File folder:folders){Session session=load(folder.getName());if(session!=null)sessions.add(session);}
        sessions.sort(Comparator.comparingLong((Session value)->value.modifiedAt).reversed());return sessions;
    }
    synchronized void setCurrent(String id)throws IOException{requireId(id);if(load(id)==null)throw new IOException("Unknown session");replaceAtomically(currentFile,id.getBytes(StandardCharsets.US_ASCII));}
    synchronized String currentId()throws IOException{return currentFile.isFile()?readText(currentFile,null):null;}
    synchronized void rename(String id,String name)throws IOException{Session session=load(id);if(session==null)throw new IOException("Unknown session");save(id,session.save,name,session.password);}
    synchronized History history(String id)throws IOException{
        requireId(id);Session session=load(id);if(session==null)throw new IOException("Unknown session");File folder=sessionFolder(id);ensureInitial(folder,session.save,session.password);
        List<HistoryRecord> records=readHistory(folder);List<HistoryEntry> entries=new ArrayList<>();File original=new File(folder,"original.bin");
        entries.add(new HistoryEntry(0,"",original.lastModified(),true));for(int i=0;i<records.size();i++){HistoryRecord record=records.get(i);entries.add(new HistoryEntry(i+1,record.label,record.modifiedAt,false));}
        return new History(entries,readCursor(folder,records.size()));
    }
    synchronized Session restoreHistory(String id,int index)throws IOException{
        requireId(id);Session session=load(id);if(session==null)throw new IOException("Unknown session");File folder=sessionFolder(id);ensureInitial(folder,session.save,session.password);List<HistoryRecord> records=readHistory(folder);
        if(index<0||index>records.size())throw new IllegalArgumentException("Invalid history index");File source=index==0?new File(folder,"original.bin"):new File(historyDirectory(folder),records.get(index-1).file);
        if(!source.isFile())throw new IOException("Missing history snapshot");byte[] restored=Files.readAllBytes(source.toPath());replaceAtomically(new File(folder,"save.bin"),restored);File restoredPassword=index==0?new File(folder,"original.password"):new File(historyDirectory(folder),records.get(index-1).file+".password");writeOptionalText(new File(folder,"password.txt"),readText(restoredPassword,null));writeCursor(folder,index);
        replaceAtomically(new File(folder,"modified.txt"),Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII));return load(id);
    }
    synchronized void delete(String id)throws IOException{
        requireId(id);File folder=sessionFolder(id);deleteRecursively(folder);
        if(id.equals(currentId())){List<Session> remaining=list();if(remaining.isEmpty())Files.deleteIfExists(currentFile.toPath());else setCurrent(remaining.get(0).id);}
    }
    synchronized void clear()throws IOException{String id=currentId();if(id!=null)delete(id);}

    private void migrateLegacy()throws IOException{
        File oldSave=new File(directory,"working-save.bin");if(!oldSave.isFile())return;
        String name=readText(new File(directory,"working-save.name"),"SAVE_DATA"),password=readText(new File(directory,"working-save.password"),null);create(Files.readAllBytes(oldSave.toPath()),name,password);
        Files.deleteIfExists(oldSave.toPath());Files.deleteIfExists(new File(directory,"working-save.name").toPath());Files.deleteIfExists(new File(directory,"working-save.password").toPath());
    }
    private File sessionFolder(String id){return new File(sessionsDirectory,id);}
    private static File historyDirectory(File folder){return new File(folder,"history");}
    private static void ensureInitial(File folder,byte[] data,String password)throws IOException{
        File original=new File(folder,"original.bin");if(!original.isFile()){replaceAtomically(original,data);writeOptionalText(new File(folder,"original.password"),password);}
        File list=new File(folder,"history.txt");if(!list.isFile())replaceAtomically(list,new byte[0]);File cursor=new File(folder,"history.cursor");if(!cursor.isFile())writeCursor(folder,0);
    }
    private static void appendHistory(File folder,byte[] data,String password,String label)throws IOException{
        List<HistoryRecord> records=readHistory(folder);int cursor=readCursor(folder,records.size());File history=historyDirectory(folder);if(!history.isDirectory()&&!history.mkdirs())throw new IOException("Cannot create history directory");
        while(records.size()>cursor){HistoryRecord removed=records.remove(records.size()-1);deleteHistorySnapshot(history,removed.file);}
        long now=System.currentTimeMillis();String filename=UUID.randomUUID()+".bin";replaceAtomically(new File(history,filename),data);writeOptionalText(new File(history,filename+".password"),password);records.add(new HistoryRecord(filename,safeHistoryLabel(label),now));
        while(records.size()>MAX_HISTORY_STEPS){HistoryRecord removed=records.remove(0);deleteHistorySnapshot(history,removed.file);}
        writeHistory(folder,records);writeCursor(folder,records.size());
    }
    private static List<HistoryRecord> readHistory(File folder)throws IOException{
        List<HistoryRecord> records=new ArrayList<>();File list=new File(folder,"history.txt");if(!list.isFile())return records;
        for(String line:Files.readAllLines(list.toPath(),StandardCharsets.UTF_8)){String[] parts=line.split("\\|",3);if(parts.length!=3)continue;try{long time=Long.parseLong(parts[0]);String file=parts[1];if(!file.matches("[A-Za-z0-9-]+\\.bin"))continue;String label=new String(Base64.getUrlDecoder().decode(parts[2]),StandardCharsets.UTF_8);records.add(new HistoryRecord(file,label,time));}catch(IllegalArgumentException ignored){}}
        return records;
    }
    private static void writeHistory(File folder,List<HistoryRecord> records)throws IOException{
        StringBuilder text=new StringBuilder();for(HistoryRecord record:records){String label=Base64.getUrlEncoder().withoutPadding().encodeToString(record.label.getBytes(StandardCharsets.UTF_8));text.append(record.modifiedAt).append('|').append(record.file).append('|').append(label).append('\n');}
        replaceAtomically(new File(folder,"history.txt"),text.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static int readCursor(File folder,int maximum)throws IOException{String value=readText(new File(folder,"history.cursor"),"0");try{return Math.max(0,Math.min(maximum,Integer.parseInt(value)));}catch(NumberFormatException ignored){return maximum;}}
    private static void writeCursor(File folder,int value)throws IOException{replaceAtomically(new File(folder,"history.cursor"),Integer.toString(value).getBytes(StandardCharsets.US_ASCII));}
    private static void writeOptionalText(File file,String value)throws IOException{if(value==null||value.isEmpty())Files.deleteIfExists(file.toPath());else replaceAtomically(file,value.getBytes(StandardCharsets.UTF_8));}
    private static void deleteHistorySnapshot(File folder,String filename)throws IOException{Files.deleteIfExists(new File(folder,filename).toPath());Files.deleteIfExists(new File(folder,filename+".password").toPath());}
    private static String safeHistoryLabel(String label){if(label==null||label.trim().isEmpty())return "edit";return label.replace('\n',' ').replace('\r',' ').trim();}
    private static void deleteRecursively(File file)throws IOException{if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)deleteRecursively(child);}Files.deleteIfExists(file.toPath());}
    private static void requireId(String id){if(id==null||!id.matches("[A-Za-z0-9-]+"))throw new IllegalArgumentException("Invalid session ID");}
    private static String readText(File file,String fallback)throws IOException{return file.isFile()?new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8):fallback;}
    private static void replaceAtomically(File target,byte[] data)throws IOException{
        File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot create session directory");File temporary=new File(parent,target.getName()+".tmp");
        try(FileOutputStream output=new FileOutputStream(temporary)){output.write(data);output.getFD().sync();}
        try{Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ignored){Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
    private static String safeName(String name){return name==null||name.trim().isEmpty()?"SAVE_DATA":name.trim();}
}
