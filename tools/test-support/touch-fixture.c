#include <linux/uinput.h>
#include <linux/input.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <stddef.h>

#if defined(FIXTURE_TYPE_A)
static const char *socket_name = "luoxianlv-audit-touch-fixture-type-a";
#else
static const char *socket_name = "luoxianlv-audit-touch-fixture";
#endif
static volatile sig_atomic_t stopping;
static void stop_signal(int value) { (void) value; stopping = 1; }

static socklen_t socket_address(struct sockaddr_un *address) {
    memset(address, 0, sizeof(*address));
    address->sun_family = AF_UNIX;
    memcpy(address->sun_path + 1, socket_name, strlen(socket_name));
    return (socklen_t) (offsetof(struct sockaddr_un, sun_path) + 1 + strlen(socket_name));
}

static int axis(int fd, unsigned short code, int minimum, int maximum) {
    struct uinput_abs_setup info = {0};
    info.code = code;
    info.absinfo.minimum = minimum;
    info.absinfo.maximum = maximum;
    if (ioctl(fd, UI_SET_ABSBIT, code) < 0) return -1;
    return ioctl(fd, UI_ABS_SETUP, &info);
}

static int emit(int fd, unsigned short type, unsigned short code, int value) {
    struct input_event event = {0};
    event.type = type; event.code = code; event.value = value;
    return write(fd, &event, sizeof(event)) == sizeof(event) ? 0 : -1;
}

static int connect_socket(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    struct sockaddr_un address;
    socklen_t address_size = socket_address(&address);
    if (connect(fd, (struct sockaddr *) &address, address_size) < 0) { close(fd); return -1; }
    return fd;
}

static int client(int argc, char **argv) {
    int fd = connect_socket();
    if (fd < 0) { perror("connect fixture"); return 1; }
    char command[128] = {0};
    if (argc == 3) snprintf(command, sizeof(command), "%s", argv[2]);
    else if (argc == 5) snprintf(command, sizeof(command), "%s %s %s", argv[2], argv[3], argv[4]);
    else if (argc == 7) snprintf(command, sizeof(command), "%s %s %s %s %s", argv[2],argv[3],argv[4],argv[5],argv[6]);
    else { close(fd); return 2; }
    write(fd, command, strlen(command));
    shutdown(fd, SHUT_WR);
    char response[128] = {0};
    ssize_t count = read(fd, response, sizeof(response) - 1);
    close(fd);
    if (count <= 0) return 1;
    puts(response);
    return strncmp(response, "OK", 2) == 0 ? 0 : 1;
}

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "send") == 0) return client(argc, argv);
    int input = open("/dev/uinput", O_RDWR | O_CLOEXEC);
    if (input < 0) { perror("open uinput"); return 1; }
    if (ioctl(input, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(input, UI_SET_KEYBIT, BTN_TOUCH) < 0 ||
        ioctl(input, UI_SET_EVBIT, EV_ABS) < 0 ||
        ioctl(input, UI_SET_PROPBIT, INPUT_PROP_DIRECT) < 0 ||
        axis(input, ABS_X, 0, 32767) < 0 || axis(input, ABS_Y, 0, 32767) < 0 ||
#if !defined(FIXTURE_TYPE_A)
        axis(input, ABS_MT_SLOT, 0, 9) < 0 ||
#endif
#if !defined(FIXTURE_ANONYMOUS)
        axis(input, ABS_MT_TRACKING_ID, 0, 65535) < 0 ||
#endif
        axis(input, ABS_MT_POSITION_X, 0, 32767) < 0 || axis(input, ABS_MT_POSITION_Y, 0, 32767) < 0) {
        perror("configure uinput"); close(input); return 1;
    }
    struct uinput_setup setup = {0};
    snprintf(setup.name, sizeof(setup.name), "Luoxianlv Audit Direct Touch");
    setup.id.bustype = BUS_USB;
    setup.id.vendor = 0x1209;
    setup.id.product = 1;
    setup.id.version = 1;
    if (ioctl(input, UI_DEV_SETUP, &setup) < 0 || ioctl(input, UI_DEV_CREATE) < 0) {
        perror("create uinput"); close(input); return 1;
    }
    int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server < 0) { perror("create socket"); ioctl(input, UI_DEV_DESTROY); close(input); return 1; }
    struct sockaddr_un address;
    socklen_t address_size = socket_address(&address);
    if (bind(server, (struct sockaddr *) &address, address_size) < 0 || listen(server, 1) < 0) {
        perror("bind socket"); close(server); ioctl(input, UI_DEV_DESTROY); close(input); return 1;
    }
    signal(SIGINT, stop_signal); signal(SIGTERM, stop_signal);
    setvbuf(stdout, NULL, _IOLBF, 0);
#if defined(FIXTURE_TYPE_A)
    printf("FIXTURE_READY uid=%d name=%s protocol=type-a\n", getuid(), setup.name);
#else
    printf("FIXTURE_READY uid=%d name=%s protocol=type-b slots=10\n", getuid(), setup.name);
#endif
    bool down = false, has_tracking = false;
#if defined(FIXTURE_TYPE_A)
    int last_x=0,last_y=0,second_x=0,second_y=0; bool second=false;
#endif
#if !defined(FIXTURE_ANONYMOUS)
    int tracking = 100;
#endif
    while (!stopping) {
        int peer = accept(server, NULL, NULL);
        if (peer < 0) { if (errno == EINTR) continue; break; }
        char command[128] = {0}, action[16] = {0}; int x = 0, y = 0;
        ssize_t count = read(peer, command, sizeof(command) - 1);
#if defined(FIXTURE_TYPE_A)
        int x2=0,y2=0;
        int parsed=count>0?sscanf(command,"%15s %d %d %d %d",action,&x,&y,&x2,&y2):0;
#else
        int parsed = count > 0 ? sscanf(command, "%15s %d %d", action, &x, &y) : 0;
#endif
        bool valid = false;
        if (parsed == 1 && strcmp(action, "stop") == 0) { stopping = 1; valid = true; }
#if defined(FIXTURE_TYPE_A)
        else if(parsed==1 && strcmp(action,"up")==0 && (down || has_tracking)) {
            valid=emit(input,EV_KEY,BTN_TOUCH,0)==0 && emit(input,EV_SYN,SYN_MT_REPORT,0)==0 && emit(input,EV_SYN,SYN_REPORT,0)==0;
            down=has_tracking=second=false;
        } else if((parsed==3 && x>=0 && x<=32767 && y>=0 && y<=32767 &&
                 ((!down && strcmp(action,"down")==0) || (down && strcmp(action,"move")==0))) ||
                 (parsed==5 && down && strcmp(action,"two")==0 && x>=0 && x<=32767 && y>=0 && y<=32767 && x2>=0 && x2<=32767 && y2>=0 && y2<=32767) ||
                 (parsed==1 && down && second && strcmp(action,"swap")==0)) {
            bool swapped=strcmp(action,"swap")==0;
#if !defined(FIXTURE_ANONYMOUS)
            if(!down)tracking++;
#endif
            if(!swapped){last_x=x;last_y=y; if(parsed==5){second=true;second_x=x2;second_y=y2;}}
            else second_y=second_y<32767?second_y+1:second_y-1;
            down=has_tracking=true;
            valid=emit(input,EV_KEY,BTN_TOUCH,1)==0;
            for(int point=0;point<(second?2:1);point++) {
                bool other=swapped?point==0:point==1;
#if !defined(FIXTURE_ANONYMOUS)
                valid=valid && emit(input,EV_ABS,ABS_MT_TRACKING_ID,tracking+(other?10000:0))==0;
#endif
                valid=valid && emit(input,EV_ABS,ABS_MT_POSITION_X,other?second_x:last_x)==0 &&
                    emit(input,EV_ABS,ABS_MT_POSITION_Y,other?second_y:last_y)==0 && emit(input,EV_SYN,SYN_MT_REPORT,0)==0;
            }
            valid=valid && emit(input,EV_ABS,ABS_X,last_x)==0 && emit(input,EV_ABS,ABS_Y,last_y)==0 && emit(input,EV_SYN,SYN_REPORT,0)==0;
        }
#else
        else if (parsed == 1 && strcmp(action, "up") == 0 && (down || has_tracking)) {
            valid = emit(input, EV_ABS, ABS_MT_SLOT, 0) == 0 &&
                    emit(input, EV_ABS, ABS_MT_TRACKING_ID, -1) == 0 &&
                    emit(input, EV_KEY, BTN_TOUCH, 0) == 0 && emit(input, EV_SYN, SYN_REPORT, 0) == 0;
            down = false;
            has_tracking = false;
        } else if (parsed == 1 && has_tracking &&
                   ((strcmp(action, "contact_on") == 0 && !down) ||
                    (strcmp(action, "contact_off") == 0 && down))) {
            down = strcmp(action, "contact_on") == 0;
            valid = emit(input, EV_KEY, BTN_TOUCH, down ? 1 : 0) == 0 &&
                    emit(input, EV_SYN, SYN_REPORT, 0) == 0;
        } else if (parsed == 3 && x >= 0 && x <= 32767 && y >= 0 && y <= 32767 &&
                   ((strcmp(action, "down") == 0 && !down) ||
                    (strcmp(action, "stale") == 0 && !down) ||
                    (strcmp(action, "move") == 0 && down))) {
            valid = emit(input, EV_ABS, ABS_MT_SLOT, 0) == 0;
            if (strcmp(action, "move") != 0) {
                valid = valid && emit(input, EV_ABS, ABS_MT_TRACKING_ID, tracking++) == 0;
                has_tracking = true;
                down = strcmp(action, "down") == 0;
            }
            valid = valid && emit(input, EV_ABS, ABS_MT_POSITION_X, x) == 0 &&
                    emit(input, EV_ABS, ABS_MT_POSITION_Y, y) == 0 &&
                    emit(input, EV_ABS, ABS_X, x) == 0 && emit(input, EV_ABS, ABS_Y, y) == 0 &&
                    emit(input, EV_KEY, BTN_TOUCH, down ? 1 : 0) == 0 && emit(input, EV_SYN, SYN_REPORT, 0) == 0;
        }
#endif
        const char *response = valid ? "OK" : "ERROR";
        write(peer, response, strlen(response)); close(peer);
        printf("FIXTURE_COMMAND %s result=%s\n", command, response);
    }
    close(server);
    ioctl(input, UI_DEV_DESTROY); close(input);
    puts("FIXTURE_CLOSED");
    return 0;
}
