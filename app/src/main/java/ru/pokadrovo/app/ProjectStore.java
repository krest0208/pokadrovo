package ru.pokadrovo.app;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Atomic local project persistence; source media remains in original SAF URIs. */
public final class ProjectStore {
    private final File folder;
    public ProjectStore(Context context){folder=new File(context.getFilesDir(),"projects");
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("Cannot create projects directory");}
    private File file(String id){
        if(!id.matches("[a-zA-Z0-9_-]{1,80}"))throw new IllegalArgumentException("Invalid project ID");
        return new File(folder,id+".json");
    }
    public void save(EditProject p) throws IOException {
        p.validate();File target=file(p.id),temp=new File(folder,p.id+".tmp");
        try(FileOutputStream s=new FileOutputStream(temp)){s.write(p.encode().getBytes(StandardCharsets.UTF_8));s.getFD().sync();}
        if(!temp.renameTo(target)) {
            try(FileInputStream in=new FileInputStream(temp);FileOutputStream out=new FileOutputStream(target)){
                byte[]b=new byte[8192];int n;while((n=in.read(b))>=0)out.write(b,0,n);out.getFD().sync();
            }if(!temp.delete())temp.deleteOnExit();
        }
    }
    public EditProject load(String id) throws IOException {
        File file=file(id);if(!file.isFile())throw new FileNotFoundException(id);
        if(file.length()>5_000_000)throw new IOException("Project is too large");
        try(FileInputStream in=new FileInputStream(file)){
            byte[] all=new byte[(int)file.length()];int off=0,n;
            while(off<all.length&&(n=in.read(all,off,all.length-off))>0)off+=n;
            return EditProject.decode(new String(all,0,off,StandardCharsets.UTF_8));
        }
    }
    public List<EditProject> list(){
        File[] files=folder.listFiles((d,n)->n.endsWith(".json"));
        ArrayList<EditProject> result=new ArrayList<>();if(files==null)return result;
        Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        for(File f:files){try{result.add(load(f.getName().replace(".json","")));}catch(Exception ignored){}}
        return result;
    }
    public boolean delete(String id){return file(id).delete();}
}
