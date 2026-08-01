package io.github.tuxkoh.bcsfe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class SessionStore {
    static final class Session {
        final String id;
        final byte[] save;
        final String name;
        final String password;
        final long modifiedAt;
        Session(String id,byte[] save,String name,String password,long modifiedAt){this.id=id;this.save=save;this.name=name;this.password=password;this.modifiedAt=modifiedAt;}
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
    synchronized void save(String id,byte[] data,String name,String password)throws IOException{
        requireId(id);File folder=sessionFolder(id);if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cannot create session directory");
        replaceAtomically(new File(folder,"save.bin"),data);replaceAtomically(new File(folder,"name.txt"),safeName(name).getBytes(StandardCharsets.UTF_8));
        File passwordFile=new File(folder,"password.txt");if(password==null||password.isEmpty())Files.deleteIfExists(passwordFile.toPath());else replaceAtomically(passwordFile,password.getBytes(StandardCharsets.UTF_8));
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
    synchronized void delete(String id)throws IOException{
        requireId(id);File folder=sessionFolder(id);if(folder.isDirectory()){File[] files=folder.listFiles();if(files!=null)for(File file:files)Files.deleteIfExists(file.toPath());Files.deleteIfExists(folder.toPath());}
        if(id.equals(currentId())){List<Session> remaining=list();if(remaining.isEmpty())Files.deleteIfExists(currentFile.toPath());else setCurrent(remaining.get(0).id);}
    }
    synchronized void clear()throws IOException{String id=currentId();if(id!=null)delete(id);}

    private void migrateLegacy()throws IOException{
        File oldSave=new File(directory,"working-save.bin");if(!oldSave.isFile())return;
        String name=readText(new File(directory,"working-save.name"),"SAVE_DATA"),password=readText(new File(directory,"working-save.password"),null);create(Files.readAllBytes(oldSave.toPath()),name,password);
        Files.deleteIfExists(oldSave.toPath());Files.deleteIfExists(new File(directory,"working-save.name").toPath());Files.deleteIfExists(new File(directory,"working-save.password").toPath());
    }
    private File sessionFolder(String id){return new File(sessionsDirectory,id);}
    private static void requireId(String id){if(id==null||!id.matches("[A-Za-z0-9-]+"))throw new IllegalArgumentException("Invalid session ID");}
    private static String readText(File file,String fallback)throws IOException{return file.isFile()?new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8):fallback;}
    private static void replaceAtomically(File target,byte[] data)throws IOException{
        File parent=target.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot create session directory");File temporary=new File(parent,target.getName()+".tmp");
        try(FileOutputStream output=new FileOutputStream(temporary)){output.write(data);output.getFD().sync();}
        try{Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ignored){Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
    private static String safeName(String name){return name==null||name.trim().isEmpty()?"SAVE_DATA":name.trim();}
}
