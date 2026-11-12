package cn.xunlian;

import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

import static cn.xunlian.CodeWorkspaceService.*;
import static cn.xunlian.Models.Account;

@Service
public class LocalCodeFiles {
    private static final int FILE_LIMIT = 20000;
    private static final int READ_LIMIT = 4 * 1024 * 1024;
    private final CodeWorkspaceService workspace;
    private final Map<Long, Folder> folders = new ConcurrentHashMap<>();
    private final ExecutorService scans = Executors.newFixedThreadPool(2);

    public LocalCodeFiles(CodeWorkspaceService workspace) { this.workspace = workspace; }

    public record OpenFolder(String path) {}
    public record FolderInfo(String path, int files) {}
    public record Hit(String file, int line, String text) {}
    public record Source(String file, int firstLine, List<String> lines, boolean more) {}
    public record JobState(String status, int processed, int total, String message, Report report) {}

    private static final class Folder {
        final Path root;
        final List<String> files;
        final Set<String> indexed;
        volatile JobState job;
        Folder(Path root, List<String> files) {
            this.root = root;
            this.files = List.copyOf(files);
            this.indexed = Set.copyOf(files);
        }
    }

    public FolderInfo open(long userId, String input) {
        try {
            Path path = Path.of(Objects.toString(input, "").strip());
            if (!path.isAbsolute() || path.toString().startsWith("\\\\")) throw invalid("请输入本机目录的绝对路径");
            Path root = path.toRealPath();
            if (!Files.isDirectory(root) || root.toString().startsWith("\\\\")) throw invalid("请选择本机目录");
            List<String> files = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (directory.equals(root)) return FileVisitResult.CONTINUE;
                    if (CodeWorkspaceService.excludedPart(directory.getFileName().toString())
                            || !directory.toRealPath().equals(directory)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        String relative = root.relativize(file).toString().replace('\\', '/');
                        if (CodeWorkspaceService.sourcePath(relative)) files.add(relative);
                        if (files.size() > FILE_LIMIT) throw invalid("目录超过 20,000 个源码文件，请选择一个子目录");
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    throw invalid("目录有无法读取的条目，请检查本机目录权限");
                }
            });
            if (files.isEmpty()) throw invalid("目录中没有可查看的 C/C++ 文件");
            files.sort(String.CASE_INSENSITIVE_ORDER);
            Folder folder = new Folder(root, files);
            folders.compute(userId, (key, old) -> {
                if (old != null && old.job != null && "running".equals(old.job.status())) {
                    throw invalid("当前目录正在扫描，请等待完成");
                }
                return folder;
            });
            return info(folder);
        } catch (IOException | InvalidPathException ex) {
            throw invalid("无法打开目录，请检查路径和访问权限");
        }
    }

    public FolderInfo current(long userId) {
        Folder folder = folders.get(userId);
        return folder == null ? null : info(folder);
    }

    private FolderInfo info(Folder folder) { return new FolderInfo(folder.root.toString(), folder.files.size()); }

    public List<Hit> search(long userId, String query, boolean content, int offset) {
        Folder folder = folder(userId);
        String needle = Objects.toString(query, "").strip().toLowerCase(Locale.ROOT);
        if (needle.length() > 160 || offset < 0 || offset > FILE_LIMIT) throw invalid("搜索条件无效");
        List<Hit> result = new ArrayList<>();
        int matched = 0;
        for (String file : folder.files) {
            if (!content || needle.isBlank()) {
                if (!file.toLowerCase(Locale.ROOT).contains(needle)) continue;
                if (matched++ >= offset) result.add(new Hit(file, 1, ""));
            } else {
                try {
                    String[] lines = read(folder, file).split("\n", -1);
                    for (int index = 0; index < lines.length; index++) {
                        if (!lines[index].toLowerCase(Locale.ROOT).contains(needle)) continue;
                        if (matched++ >= offset) result.add(new Hit(file, index + 1,
                                lines[index].substring(0, Math.min(lines[index].length(), 240))));
                        break;
                    }
                } catch (IOException ignored) { /* Unreadable files are counted during the scan. */ }
            }
            if (result.size() == 60) break;
        }
        return result;
    }

    public Source view(long userId, String file, int firstLine) {
        Folder folder = folder(userId);
        if (firstLine < 1 || !folder.indexed.contains(file)) throw invalid("文件或行号不在当前目录内");
        try {
            String[] lines = read(folder, file).split("\n", -1);
            if (firstLine > lines.length) throw invalid("行号已超出文件范围");
            int end = Math.min(lines.length, firstLine - 1 + 120);
            List<String> page = new ArrayList<>();
            for (int i = firstLine - 1; i < end; i++) {
                String line = lines[i];
                page.add(line.length() > 2000 ? line.substring(0, 2000) + " …" : line);
            }
            return new Source(file, firstLine, page, end < lines.length);
        } catch (IOException ex) {
            throw invalid("文件已移除、无法读取或超过 4 MiB");
        }
    }

    public JobState start(Account account, boolean useModel) {
        Folder folder = folder(account.id());
        synchronized (folder) {
            if (folder.job != null && "running".equals(folder.job.status())) return folder.job;
            int total = folder.files.size();
            folder.job = new JobState("running", 0, total, "", null);
            scans.submit(() -> scan(account, folder, useModel));
            return folder.job;
        }
    }

    public JobState status(long userId) { return folder(userId).job; }

    private void scan(Account account, Folder folder, boolean useModel) {
        int done = 0;
        int total = folder.files.size();
        String scanId = null;
        try {
            String name = folder.root.getFileName() == null ? "本机磁盘" : folder.root.getFileName().toString();
            scanId = workspace.startFolderScan(account.id(), new StartScan(name, total, useModel)).id();
            for (String file : folder.files) {
                String source;
                try { source = read(folder, file); } catch (IOException ex) { source = ""; }
                workspace.scanLocalFile(account.id(), scanId, file, source);
                folder.job = new JobState("running", ++done, total, "", null);
            }
            Report report = workspace.finishFolderScan(account, scanId);
            folder.job = new JobState("done", total, total, "", report);
        } catch (RuntimeException ex) {
            folder.job = new JobState("error", done, total, "扫描未完成，请检查目录并重试", null);
        } finally {
            if (scanId != null) workspace.cancelFolderScan(account.id(), scanId);
        }
    }

    private String read(Folder folder, String file) throws IOException {
        Path path = folder.root.resolve(file).normalize();
        Path real = path.toRealPath();
        if (!real.startsWith(folder.root) || !real.equals(path)
                || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Path outside folder");
        byte[] bytes;
        try (var stream = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS)) {
            bytes = stream.readNBytes(READ_LIMIT + 1);
        }
        if (bytes.length > READ_LIMIT) throw new IOException("File exceeds read limit");
        String text;
        if (bytes.length >= 2 && ((bytes[0] == -1 && bytes[1] == -2) || (bytes[0] == -2 && bytes[1] == -1))) {
            text = decode(bytes, StandardCharsets.UTF_16);
        } else {
            try { text = decode(bytes, StandardCharsets.UTF_8); }
            catch (CharacterCodingException ex) { text = decode(bytes, Charset.forName("GB18030")); }
        }
        if (text.indexOf('\0') >= 0) throw new IOException("Binary file");
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }

    private Folder folder(long userId) {
        Folder folder = folders.get(userId);
        if (folder == null) throw invalid("请先打开本地目录");
        return folder;
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    @PreDestroy
    public void shutdown() { scans.shutdownNow(); }
}
