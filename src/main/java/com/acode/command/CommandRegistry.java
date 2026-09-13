package com.acode.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 命令注册中心：读写锁保护（读读并发、读写与写写互斥），运行期注册与查找并发安全。
 * 名称与别名统一小写化后做占用检测；按名查找先精确匹配正式名、未命中再查别名（别名与正式名完全等价）。
 * 可见清单（供帮助与补全）按注册顺序排列、排除隐藏命令；全部清单（供内部使用）含隐藏命令。
 */
public class CommandRegistry {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    /** 正式名（小写）→ 命令；LinkedHashMap 保持注册顺序 */
    private final Map<String, Command> byName = new LinkedHashMap<>();
    /** 别名（小写）→ 命令 */
    private final Map<String, Command> byAlias = new LinkedHashMap<>();

    /** 注册一条命令：名称与所有别名都做占用检测，任一冲突即抛错
     * （错误信息含冲突的那个名字与已占用它的命令名），且不改变注册中心现有状态 */
    public CommandRegistry register(Command command) {
        if (command == null || command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("命令名称不能为空");
        }
        String nameKey = lower(command.name());
        List<String> aliasKeys = new ArrayList<>();
        for (String alias : command.aliases()) {
            aliasKeys.add(lower(alias));
        }
        lock.writeLock().lock();
        try {
            Set<String> seen = new HashSet<>();
            checkOccupied(nameKey, "命令名", command, seen);
            for (String key : aliasKeys) {
                checkOccupied(key, "别名", command, seen);
            }
            byName.put(nameKey, command);
            for (String key : aliasKeys) {
                byAlias.put(key, command);
            }
        } finally {
            lock.writeLock().unlock();
        }
        return this;
    }

    /** 按名查找：大小写不敏感；未注册返回 null */
    public Command find(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = lower(name);
        lock.readLock().lock();
        try {
            Command command = byName.get(key);
            return command != null ? command : byAlias.get(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 可见清单（供帮助与补全）：按注册顺序、排除隐藏命令 */
    public List<Command> visible() {
        lock.readLock().lock();
        try {
            return byName.values().stream().filter(c -> !c.hidden()).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 全部命令清单（含隐藏，供内部使用）：按注册顺序 */
    public List<Command> all() {
        lock.readLock().lock();
        try {
            return List.copyOf(byName.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    private void checkOccupied(String key, String kind, Command registering, Set<String> seen) {
        if (!seen.add(key)) {
            throw new IllegalArgumentException(kind + " " + key + " 已被命令 " + registering.name() + " 占用");
        }
        Command owner = byName.get(key);
        if (owner == null) {
            owner = byAlias.get(key);
        }
        if (owner != null) {
            throw new IllegalArgumentException(kind + " " + key + " 已被命令 " + owner.name() + " 占用");
        }
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
