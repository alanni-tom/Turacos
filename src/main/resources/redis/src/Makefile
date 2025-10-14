# ==== Linux (GCC) ====
linux32:
	gcc -m32 -fPIC -O2 -std=c99 -I. -shared redis_sys.c -o ../redis.so

linux64:
	gcc -m64 -fPIC -O2 -std=c99 -I. -shared redis_sys.c -o ../redis.so

# ==== Windows (MSVC, run in Developer Command Prompt) ====
win32:
	cl /nologo /LD /O2 /I . redis_sys.c /Fe:..\redis.dll /link /MACHINE:X86

win64:
	cl /nologo /LD /O2 /I . redis_sys.c /Fe:..\redis.dll /link /MACHINE:X64

# ==== Windows (MinGW) ====
mingw32:
	gcc -m32 -O2 -std=c99 -I. -shared -Wl,--out-implib,../redis32.lib redis_sys.c -o ../redis.dll

mingw64:
	gcc -m64 -O2 -std=c99 -I. -shared -Wl,--out-implib,../redis64.lib redis_sys.c -o ../redis.dll