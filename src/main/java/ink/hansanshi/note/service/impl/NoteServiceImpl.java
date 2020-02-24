package ink.hansanshi.note.service.impl;

import ink.hansanshi.note.dao.DelNoteRepository;
import ink.hansanshi.note.dao.DraftNoteRepository;
import ink.hansanshi.note.dto.ServerResponse;
import ink.hansanshi.note.entity.DelNoteDo;
import ink.hansanshi.note.service.IFileService;
import ink.hansanshi.note.service.INoteService;
import ink.hansanshi.note.util.GitUtil;
import ink.hansanshi.note.util.ThreadLocalUtil;
import ink.hansanshi.note.util.ValidateUtil;
import ink.hansanshi.note.vo.DeletedNoteVo;
import ink.hansanshi.note.vo.NoteVersionVo;
import ink.hansanshi.note.vo.NoteVo;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author hansanshi
 * @date 2019/12/28
 */
@Service
@Slf4j
public class NoteServiceImpl implements INoteService {

    @Value("${notesDir}")
    private String notesDir;

    @Autowired
    private IFileService fileService;

    @Autowired
    private DelNoteRepository delNoteRepository;

    @Autowired
    private DraftNoteRepository draftNoteRepository;

    private static final String NOTEBOOK_FLAG_FILE = ".notebook";


    @Override
    public ServerResponse<List<String>> listNotebooks(){
        File dir = getOrCreateUserNotebookDir();
        File[] childFiles = dir.listFiles();
        if (childFiles == null || childFiles.length == 0){
            return ServerResponse.buildSuccessResponse(Collections.emptyList());
        }
        List<String> notebookNameList = new ArrayList<>();
        for (File file : childFiles){
            if (file.isDirectory()){
                String fileName = file.getName();
                if (fileName.startsWith(".")){
                    continue;
                }
                notebookNameList.add(file.getName());
            }
        }
        return ServerResponse.buildSuccessResponse(notebookNameList);
    }

    @Override
    public ServerResponse<List<NoteVo>> listNotes(String notebookName){
        File notebookDir = new File(getOrCreateUserNotebookDir(),notebookName);
        if (!notebookDir.exists() || notebookDir.isFile()){
            throw new RuntimeException("No such notebook");
        }
        File[] childFiles = notebookDir.listFiles();
        if (childFiles == null || childFiles.length == 0 ){
            return ServerResponse.buildSuccessResponse(Collections.emptyList());
        }
        List<String> noteList = new ArrayList<>();
        for (File file : childFiles){
            if (file.isDirectory()){
                continue;
            }
            if (checkExtension(file.getName())){
                noteList.add(file.getName().substring(0,file.getName().lastIndexOf(".")));
            }
        }
        List<NoteVo> noteVoList = new ArrayList<>(noteList.size());
        noteList.forEach( note -> noteVoList.add(new NoteVo().setTitle(note)));
        return ServerResponse.buildSuccessResponse(noteVoList);
    }



    private void createNotebookIfNecessary(String notebookName){
        File notebookDir = new File(getOrCreateUserNotebookDir(),notebookName);
        if (notebookDir.exists()){
            return ;
        }
        createNotebook(notebookName);
    }

    @Override
    public ServerResponse createNotebook(String notebookName){
        File notebookDir = new File(getOrCreateUserNotebookDir(), notebookName);
        if (!notebookDir.mkdir()) {
            throw new RuntimeException("Create notebook failed");
        }

        File notebookFlagFile = new File(notebookDir, NOTEBOOK_FLAG_FILE);
        try {
            if (!notebookFlagFile.createNewFile()) {
                throw new RuntimeException("Create notebook failed");
            }
        } catch (IOException e) {
            throw new RuntimeException("Create notebook failed");
        }
        String relativeName = notebookName + "/" + NOTEBOOK_FLAG_FILE;
        GitUtil.addAndCommit(getOrCreateUserGit(), relativeName);
        log.info("create notebook: {}", notebookName);
        return ServerResponse.buildSuccessResponse();
    }

    @Override
    public ServerResponse createNote(String noteTitle, String notebookName, String content){
        File targetFile =  new File(getOrCreateUserNotebookDir(), getRelativeFileName(notebookName, noteTitle));
        if (targetFile.exists()){
            throw new RuntimeException("Note already exists");
        }
        return saveNote(noteTitle, notebookName, content);
    }

    @Override
    public ServerResponse saveNote(String noteTitle, String notebookName, String content) {

        createNotebookIfNecessary(notebookName);

        String relativeFileName = getRelativeFileName(notebookName,noteTitle);
        File noteFile = new File(getOrCreateUserNotebookDir(), relativeFileName);
        try {
            if (!noteFile.exists() && !noteFile.createNewFile()){
                throw new RuntimeException("Save note failed");
            }
        } catch (IOException e) {
            log.error("save note error", e);
        }

        //write file
        fileService.writeStringToFile(content,noteFile);
        GitUtil.addAndCommit(getOrCreateUserGit(),relativeFileName);

        draftNoteRepository.deleteByUsernameAndNotebookNameAndTitle(getUsername(), notebookName, noteTitle);
        return ServerResponse.buildSuccessResponse();
    }

    @Override
    public ServerResponse deleteNote(String notebookName, String noteTitle){
        String relativeFileName = getRelativeFileName(notebookName,noteTitle);
        File noteFile = new File(getOrCreateUserNotebookDir(), relativeFileName);
        String content = getNote(notebookName, noteTitle).getData();
        if (!noteFile.exists() || !noteFile.delete()){
            return ServerResponse.buildErrorResponse("Can't delete note");
        }
        String lastRef = GitUtil.getFileCurRef(getOrCreateUserGit(),relativeFileName);
        GitUtil.rmAndCommit(getOrCreateUserGit(),relativeFileName);
        delNoteRepository.save(new DelNoteDo().setNotebook(notebookName)
                                    .setTitle(noteTitle)
                                    .setLastRef(lastRef)
                                    .setContent(content)
                                    .setUsername(getUsername()));
        return ServerResponse.buildSuccessResponse();
    }

    @Override
    public ServerResponse recoverNote(Integer id){
        DelNoteDo delNoteDO = delNoteRepository.findByIdAndUsername(id, getUsername());
        String relativeFileName = getRelativeFileName(delNoteDO.getNotebook(), delNoteDO.getTitle());
        File noteFile = new File(getOrCreateUserNotebookDir(), relativeFileName);
        if (noteFile.exists()){
            return ServerResponse.buildErrorResponse("Note already exists");
        }
        GitUtil.recoverDeletedFile(getOrCreateUserGit(), relativeFileName, delNoteDO.getLastRef());
        return clearDelNote(id);
    }


    @Override
    public ServerResponse<String> getNote(String notebookName, String noteTitle){
        String relativeFileName = getRelativeFileName(notebookName,noteTitle);
        File noteFile = new File(getOrCreateUserNotebookDir(), relativeFileName);
        if (!noteFile.exists()){
            return ServerResponse.buildErrorResponse("Note not exists");
        }
        String content = fileService.getContentFromFile(noteFile);
        if (content == null){
            return ServerResponse.buildErrorResponse("Read note failed");
        }
        return ServerResponse.buildSuccessResponse(content);
    }


    @Override
    public ServerResponse<List<NoteVersionVo>> getNoteHistory(String notebookName, String noteTitle){
        String relativeFileName = getRelativeFileName(notebookName,noteTitle);
        List<NoteVersionVo> noteVersionVoList = GitUtil.getNoteHistory(getOrCreateUserGit(),relativeFileName);
        return ServerResponse.buildSuccessResponse(noteVersionVoList);
    }

    @Override
    public ServerResponse<String> resetAndGet(String notebookName, String noteTitle, String versionRef){
        String relativeFileName = getRelativeFileName(notebookName,noteTitle);
        boolean result = GitUtil.resetAndCommit(getOrCreateUserGit(),relativeFileName,versionRef);
        if (!result){
            return ServerResponse.buildErrorResponse("Recover to history version failed");
        }
        return getNote(notebookName, noteTitle);
    }

    @Override
    public ServerResponse deleteNotebook(String notebookName){
        File notebookDir = new File(getOrCreateUserNotebookDir(), notebookName);
        listNotes(notebookName).getData().forEach(noteVo -> deleteNote(notebookName, noteVo.getTitle()));
        fileService.deleteFile(notebookDir);
        GitUtil.rmAndCommit(getOrCreateUserGit(),notebookName + "/" + NOTEBOOK_FLAG_FILE);
        return ServerResponse.buildSuccessResponse();
    }

    /**
     * get deleted notes
     * @return
     */
    @Override
    public ServerResponse<List<DeletedNoteVo>> listDelNotes(){
        List<DeletedNoteVo> deletedNoteList = new ArrayList<>();
        delNoteRepository.findAllByUsername(getUsername())
                .forEach(delNoteDo -> deletedNoteList.add(new DeletedNoteVo()
                    .setId(delNoteDo.getId())
                    .setTitle(delNoteDo.getTitle())
                    .setNotebook(delNoteDo.getNotebook())
                    .setLastRef(delNoteDo.getLastRef())
                    .setUsername(delNoteDo.getUsername())
                    .setContent(delNoteDo.getContent())
        ));
        return ServerResponse.buildSuccessResponse(deletedNoteList);
    }

    @Override
    public ServerResponse clearDelNote(@NonNull Integer id){
        delNoteRepository.deleteByIdAndUsername(id, getUsername());
        return ServerResponse.buildSuccessResponse();
    }

    @Override
    public ServerResponse copyNote(String srcNotebook, String targetNotebook, String title) {
        if (srcNotebook.equals(targetNotebook)){
            throw new RuntimeException("Same notebook");
        }
        ServerResponse<String> response = getNote(srcNotebook, title);
        if (!response.isSuccess()){
            return response;
        }
        String content = response.getData();
        return createNote(title, targetNotebook, content);
    }

    @Override
    public ServerResponse moveNote(String srcNotebook, String srcTitle, String targetNotebook, String targetTitle){
        // src != target
        if (srcNotebook.equalsIgnoreCase(targetNotebook) && srcTitle.equalsIgnoreCase(targetTitle)){
            throw new IllegalArgumentException();
        }
        ValidateUtil.notBlankString(srcNotebook, srcTitle, targetNotebook, targetTitle);
        ServerResponse<String> response = getNote(srcNotebook, srcTitle);
        if (!response.isSuccess()){
            return response;
        }
        String content = response.getData();
        String targetRelativeName = getRelativeFileName(targetNotebook, targetTitle);
        File targetFile =  new File(getOrCreateUserNotebookDir(), targetRelativeName);
        if (targetFile.exists()){
            throw new RuntimeException("Note already exists");
        }
        String srcRelativeName = getRelativeFileName(srcNotebook, srcTitle);
        File srcFile = new File(getOrCreateUserNotebookDir(), srcRelativeName);
        fileService.deleteFile(srcFile);
        fileService.writeStringToFile(content, targetFile);
        GitUtil.mvAndCommit(getOrCreateUserGit(), srcRelativeName, targetRelativeName);
        return ServerResponse.buildSuccessResponse();
    }

    @Override
    @Transactional
    public ServerResponse clearAllDelNotes(){
        delNoteRepository.deleteAllByUsername(getUsername());
        return ServerResponse.buildSuccessResponse();
    }

    private String getRelativeFileName(String notebookName, String noteTitle) {

        return notebookName + "/" + noteTitle+".md";
    }

    private File getOrCreateUserNotebookDir(){
        File dir = new File(notesDir, getUsername());
        if (dir.exists()){
            return dir;
        }
        dir.mkdir();
        return dir;
    }

    private String getUsername(){
        return ThreadLocalUtil.getUsername();
    }

    private File getUserNotebookDir(){
        File dir = new File(notesDir, ThreadLocalUtil.getUsername());
        if (dir.exists()){
            return dir;
        }
        return null;
    }

    private Git getOrCreateUserGit(){
        return GitUtil.getOrInitGit(getOrCreateUserNotebookDir());
    }

    private boolean checkExtension(String filename){
        return filename.endsWith(".md")
                || filename.endsWith(".MD")
                || filename.endsWith(".mD")
                || filename.endsWith(".Md");
    }
}
