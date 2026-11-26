package cn.xunlian;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
public class CoachApplication {
    public static void main(String[] args) {
        prepareSqliteDirectory();
        SpringApplication.run(CoachApplication.class, args);
    }

    /** SQLite 不会自动创建目录，首次启动时先把 data/ 建好，避免连库失败。 */
    private static void prepareSqliteDirectory() {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:sqlite:./data/xunlian.db");
        if (!url.startsWith("jdbc:sqlite:")) return;
        String path = url.substring("jdbc:sqlite:".length());
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        if (path.isBlank() || path.startsWith(":memory:") || path.startsWith("file:")) return;
        try {
            Path absolute = Paths.get(path).toAbsolutePath();
            Path parent = absolute.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception ex) {
            throw new IllegalStateException("无法创建数据库目录：" + path, ex);
        }
    }
}
