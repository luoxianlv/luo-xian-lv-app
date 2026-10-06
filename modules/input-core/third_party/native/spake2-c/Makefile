CC :=gcc

ifeq ($(OS),Windows_NT)
	detected_os := Windows
else
	detected_os := $(shell sh -c 'uname 2>/dev/null || echo Unknown')
endif

ifeq ($(detected_os),Darwin)
	lib_name := libspake2.dylib
	lib_cflags := -dynamiclib -lc
endif
ifeq ($(detected_os),Linux)
	lib_name := libspake2.so
	lib_cflags := -shared -fPIC -lc
endif
ifeq ($(detected_os),Windows)
	lib_name := spake2.dll
	lib_cflags := -shared -Wl,--add-stdcall-alias
endif

ifeq ($(PREFIX),)
    PREFIX := /usr/local
endif

build: spake2.c sha512.c ed25519.h curve25519_32.h
	$(CC) -c -fPIC -Wall -O3 -Iinclude/ sha512.c spake2.c
	$(CC) $(lib_cflags) -o $(lib_name) sha512.o spake2.o

install: build
	install -d $(DESTDIR)$(PREFIX)/lib/
	install -m 644 $(lib_name) $(DESTDIR)$(PREFIX)/lib/
	install -d $(DESTDIR)$(PREFIX)/include/spake2
	install -m 644 include/spake2/* $(DESTDIR)$(PREFIX)/include/spake2

test: test.c build
	$(CC) -o test -Iinclude/ -L. -lspake2 test.c
	./test

.PHONY: clean

clean:
	rm -f libspake2.so libspake2.dylib spake2.dll
	rm -f *.o
	rm -f test
