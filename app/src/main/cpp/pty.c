// Minimal pseudo-terminal helper: lets the SSH server give clients a real
// interactive terminal (job control, Ctrl-C, full-screen apps like vi/top).
#define _GNU_SOURCE
#include <jni.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static void throw_io(JNIEnv *env, const char *what) {
    char msg[256];
    snprintf(msg, sizeof msg, "%s: %s", what, strerror(errno));
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls) (*env)->ThrowNew(env, cls, msg);
}

static char *dup_jstring(JNIEnv *env, jstring s) {
    const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
    char *out = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, s, utf);
    return out;
}

static char **to_cstrings(JNIEnv *env, jobjectArray arr) {
    jsize n = arr ? (*env)->GetArrayLength(env, arr) : 0;
    char **out = calloc((size_t) n + 1, sizeof(char *));
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        out[i] = dup_jstring(env, s);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static void free_cstrings(char **a) {
    if (!a) return;
    for (char **p = a; *p; p++) free(*p);
    free(a);
}

JNIEXPORT jint JNICALL
Java_dev_rootssh_Pty_createSubprocess(JNIEnv *env, jobject thiz, jstring jcmd,
                                      jobjectArray jargs, jobjectArray jenv, jstring jcwd,
                                      jint rows, jint cols, jintArray jpid) {
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) {
        throw_io(env, "open /dev/ptmx");
        return -1;
    }
    char devname[64];
    if (grantpt(ptm) || unlockpt(ptm) || ptsname_r(ptm, devname, sizeof devname)) {
        throw_io(env, "pty setup");
        close(ptm);
        return -1;
    }

    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);  // don't let Ctrl-S freeze the session
        tcsetattr(ptm, TCSANOW, &tios);
    }
    struct winsize ws = {.ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols};
    ioctl(ptm, TIOCSWINSZ, &ws);

    char *cmd = dup_jstring(env, jcmd);
    char *cwd = dup_jstring(env, jcwd);
    char **argv = to_cstrings(env, jargs);
    char **envp = to_cstrings(env, jenv);

    pid_t pid = fork();
    if (pid < 0) {
        throw_io(env, "fork");
        free(cmd);
        free(cwd);
        free_cstrings(argv);
        free_cstrings(envp);
        close(ptm);
        return -1;
    }

    if (pid == 0) {
        sigset_t set;
        sigemptyset(&set);
        sigprocmask(SIG_SETMASK, &set, NULL);
        for (int s = 1; s < 32; s++) signal(s, SIG_DFL);

        close(ptm);
        setsid();
        int pts = open(devname, O_RDWR);
        if (pts < 0) _exit(126);
        ioctl(pts, TIOCSCTTY, 0);
        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);

        DIR *d = opendir("/proc/self/fd");
        if (d) {
            int dfd = dirfd(d);
            struct dirent *e;
            while ((e = readdir(d)) != NULL) {
                int fd = atoi(e->d_name);
                if (fd > 2 && fd != dfd) close(fd);
            }
            closedir(d);
        }

        clearenv();
        for (char **p = envp; *p; p++) putenv(*p);
        if (chdir(cwd) != 0) chdir("/");
        execvp(cmd, argv);

        char msg[512];
        int len = snprintf(msg, sizeof msg, "androopenheart: cannot run %s: %s\r\n", cmd, strerror(errno));
        if (len > 0) write(2, msg, (size_t) len);
        _exit(127);
    }

    free(cmd);
    free(cwd);
    free_cstrings(argv);
    free_cstrings(envp);
    jint p = pid;
    (*env)->SetIntArrayRegion(env, jpid, 0, 1, &p);
    return ptm;
}

JNIEXPORT void JNICALL
Java_dev_rootssh_Pty_setWindowSize(JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    struct winsize ws = {.ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols};
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_dev_rootssh_Pty_waitFor(JNIEnv *env, jobject thiz, jint pid) {
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 0;
}

JNIEXPORT jint JNICALL
Java_dev_rootssh_Pty_sendSignal(JNIEnv *env, jobject thiz, jint pid, jint sig) {
    return kill(pid, sig);
}
