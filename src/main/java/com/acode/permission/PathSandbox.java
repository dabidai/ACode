package com.acode.permission;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 路径沙箱：文件工具的目标路径先解析符号链接再做前缀校验，
 * 必须落在项目根目录或系统临时目录（java.io.tmpdir）内。
 * fail closed：解析失败按拒绝处理（新建文件走父目录兜底，不误伤）。
 */
public class PathSandbox {

    private final Path projectRoot;
    private final List<Path> allowedRoots;

    public PathSandbox(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.allowedRoots = List.of(
                resolveRoot(this.projectRoot),
                resolveRoot(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
        );
    }

    /** 目标路径解析后落在任一允许根内 → true；解析失败或越界 → false */
    public boolean check(String path) {
        Path resolved = resolveTarget(path);
        if (resolved == null) {
            return false;
        }
        for (Path root : allowedRoots) {
            if (resolved.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    public String denyReason(String path) {
        return "路径 " + path + " 超出沙箱范围";
    }

    /** 解析目标路径的 canonical 真实路径（供 plan 例外判断用）；解析失败返回 null */
    public Path canonical(String path) {
        return resolveTarget(path);
    }

    /** 允许根自身也解析符号链接，保证「项目根是符号链接」时目标 real path 仍能命中 */
    private static Path resolveRoot(Path root) {
        try {
            return root.toRealPath();
        } catch (IOException e) {
            return root;
        }
    }

    /**
     * ~ 展开 → 相对路径基于项目根解析为绝对路径 → 解析符号链接。
     * 目标文件不存在时解析父目录（父目录存在则取「父目录 real path + 文件名」）；
     * 父目录也不存在返回 null（fail closed）。
     */
    private Path resolveTarget(String path) {
        if (path == null) {
            return null;
        }
        Path candidate = toCandidate(path);
        try {
            return candidate.toRealPath();
        } catch (IOException e) {
            Path parent = candidate.getParent();
            if (parent == null) {
                return null;
            }
            try {
                return parent.toRealPath().resolve(candidate.getFileName());
            } catch (IOException e2) {
                return null;
            }
        }
    }

    private Path toCandidate(String path) {
        String expanded = expandHome(path);
        Path p = Path.of(expanded);
        return p.isAbsolute() ? p : projectRoot.resolve(p);
    }

    private static String expandHome(String path) {
        if (path.equals("~")) {
            return System.getProperty("user.home");
        }
        if (path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
