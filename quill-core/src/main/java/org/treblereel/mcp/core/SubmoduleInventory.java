package org.treblereel.mcp.core;
import java.nio.file.*;
import java.util.*;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.submodule.SubmoduleWalk;

/** Read-only local Git links and their own worktree evidence; never initializes, fetches or updates. */
public final class SubmoduleInventory {
    public record Entry(String path,String indexCommit,String headCommit,boolean initialized,String status,String fingerprint,
            String structuralFingerprint,boolean structuralDirty) {
        Entry(String path,String indexCommit,String headCommit,boolean initialized,String status,String fingerprint) {
            this(path,indexCommit,headCommit,initialized,status,fingerprint,fingerprint,!"clean".equals(status));
        }
    }
    public static List<Entry> read(Path root) {
        Path git=GitAnalyzer.findGitDir(root);if(git==null)return List.of();
        try(var repository=new FileRepositoryBuilder().setGitDir(git.toFile()).build()){return inspect(repository,0);}
        catch(Exception unavailable){return List.of(new Entry(".",null,null,false,"unavailable","unavailable"));}
    }
    static List<Entry> inspect(Repository repository,int depth) {
        List<Entry> entries=new ArrayList<>();
        try(var walk=SubmoduleWalk.forIndex(repository)) {
            Path root=repository.getWorkTree().toPath().toRealPath();
            while(walk.next()) {
                String path=walk.getPath(),index=walk.getObjectId().name();
                Path directory=root.resolve(path).normalize();
                if(!directory.startsWith(root) || directory.equals(root)){entries.add(new Entry(path,index,null,false,"invalid_path","invalid_path"));continue;}
                try(var child=walk.getRepository()) {
                    if(child==null){entries.add(new Entry(path,index,null,false,"uninitialized","uninitialized"));continue;}
                    if(!directory.toRealPath().startsWith(root) || !child.getWorkTree().toPath().toRealPath().equals(directory.toRealPath())) {
                        entries.add(new Entry(path,index,null,false,"invalid_worktree","invalid_worktree"));continue;
                    }
                    var head=child.resolve("HEAD");String sha=head==null?null:head.name();
                    if(depth>=8){entries.add(new Entry(path,index,sha,true,"nested_depth_limit","unknown"));continue;}
                    var state=WorktreeInspector.inspect(directory,depth+1);
                    String status=sha!=null && state.currentCommit()==null ? "unavailable" : !Objects.equals(index,sha)?"commit_changed":state.dirty()?"dirty":"clean";
                    entries.add(new Entry(path,index,sha,true,status,index+":"+sha+":"+state.fingerprint(),
                            index+":"+sha+":"+state.structuralFingerprint(),!Objects.equals(index,sha) || state.structuralDirty() || "unavailable".equals(status)));
                } catch(Exception unavailable){entries.add(new Entry(path,index,null,false,"unavailable","unavailable"));}
            }
        }catch(Exception unavailable){entries.add(new Entry(".",null,null,false,"unavailable","unavailable"));}
        return List.copyOf(entries);
    }
    private SubmoduleInventory(){}
}
