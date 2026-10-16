#define _GNU_SOURCE 1
#include <errno.h>
#include <stdint.h>
#include <limits.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <unistd.h>

/* Android Scudo reserves ~10 GiB of sparse virtual space before decoding.
 * Limit private writable data separately from the whole address space. */
#ifndef ALLDOC_NATIVE_AS_MIB
#define ALLDOC_NATIVE_AS_MIB 16384
#endif
#ifndef ALLDOC_NATIVE_DATA_MIB
#define ALLDOC_NATIVE_DATA_MIB 768
#endif

static int failure(int code, const char *message, size_t length) {
    /* Fixed diagnostics only: never print paths, argv, passwords, or errno text. */
    ssize_t ignored = write(STDERR_FILENO, message, length);
    (void)ignored;
    /* A lowered/exhausted limit can make libc teardown allocate and abort. */
    _exit(code);
}

static int lower_limit(int resource, rlim_t budget) {
    struct rlimit limit;
    if (getrlimit(resource, &limit) != 0) return -1;
    /* Preserve stricter inherited limits; never raise either boundary. */
    if (limit.rlim_cur == RLIM_INFINITY || limit.rlim_cur > budget)
        limit.rlim_cur = budget;
    if (limit.rlim_max == RLIM_INFINITY || limit.rlim_max > limit.rlim_cur)
        limit.rlim_max = limit.rlim_cur;
    if (setrlimit(resource, &limit) != 0 || getrlimit(resource, &limit) != 0
            || limit.rlim_cur > budget || limit.rlim_max > budget) return -1;
    return 0;
}

static int mapping_rejected(size_t size, int protection) {
    errno = 0;
    void *region = mmap(NULL, size, protection,
                       MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    if (region != MAP_FAILED) {
        munmap(region, size);
        return -1;
    }
    return errno == ENOMEM ? 0 : -1;
}

static int enforce_limits(void) {
    const rlim_t address_space = (rlim_t)ALLDOC_NATIVE_AS_MIB * 1024 * 1024;
    const rlim_t private_data = (rlim_t)ALLDOC_NATIVE_DATA_MIB * 1024 * 1024;
    if (lower_limit(RLIMIT_AS, address_space) || lower_limit(RLIMIT_DATA, private_data)
            || lower_limit(RLIMIT_STACK, (rlim_t)8 * 1024 * 1024))
        return -1;
    /* Check enforcement, including kernels predating mmap's RLIMIT_DATA support.
     * No pages are touched or committed: MAP_NORESERVE and PROT_NONE are used
     * for the temporary reservation. Unsupported behavior fails closed. */
    if (mapping_rejected((size_t)address_space + 65536, PROT_NONE)
            || mapping_rejected((size_t)private_data + 65536, PROT_READ | PROT_WRITE))
        return -1;
    const size_t probe_size = (size_t)private_data + 65536;
    void *region = mmap(NULL, probe_size, PROT_NONE,
                       MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    if (region == MAP_FAILED) return -1;
    errno = 0;
    const int result = mprotect(region, probe_size, PROT_READ | PROT_WRITE);
    const int error = errno;
    const int released = munmap(region, probe_size);
    return result == -1 && error == ENOMEM && released == 0 ? 0 : -1;
}

static int watch_parent(const char *value) {
    char *end = NULL;
    errno = 0;
    const long parent = strtol(value, &end, 10);
    if (errno || end == value || *end || parent <= 1 || parent > INT_MAX)
        return -1;
    /* The caller supplies its PID: getppid alone cannot detect death before
     * launcher entry. Check both sides of prctl to close the registration race. */
    if (getppid() != (pid_t)parent || prctl(PR_SET_PDEATHSIG, SIGKILL) != 0
            || getppid() != (pid_t)parent)
        return -1;
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 3 || argv[2][0] != '/')
        return failure(64, "AllDoc: invalid native invocation\n",
                       sizeof("AllDoc: invalid native invocation\n") - 1);
    if (watch_parent(argv[1]) != 0)
        return failure(124, "AllDoc: native parent unavailable\n",
                       sizeof("AllDoc: native parent unavailable\n") - 1);
    if (enforce_limits() != 0)
        return failure(125, "AllDoc: native memory limit unavailable\n",
                       sizeof("AllDoc: native memory limit unavailable\n") - 1);
    /* exec keeps this PID, inherited descriptors and cancellation ownership. */
    execv(argv[2], argv + 2);
    return failure(126, "AllDoc: native execution failed\n",
                   sizeof("AllDoc: native execution failed\n") - 1);
}
