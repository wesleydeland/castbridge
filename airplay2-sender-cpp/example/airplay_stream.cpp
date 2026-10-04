// SPDX-License-Identifier: Apache-2.0
// airplay_stream - stream live s16le stereo PCM from stdin to an AirPlay receiver.
// usage: ffmpeg ... -f s16le - | airplay_stream <receiver-ip> --rate 44100 --homepod
#include "raop_loop.h"
#include "raop_sender.h"
#include "ring_buffer.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <csignal>
#include <ctime>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <span>
#include <string>
#include <thread>
#include <unistd.h>
#include <cerrno>

using namespace fxchain;

namespace {
std::atomic<bool> g_interrupted{false};
void onSignal(int) { g_interrupted.store(true); }

std::string readFile(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return {};
    return std::string((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}
bool writeFile(const std::string& path, const std::string& data) {
    std::ofstream f(path, std::ios::binary | std::ios::trunc);
    if (!f) return false;
    f << data;
    return bool(f);
}
void usage() {
    std::fprintf(stderr,
        "usage: airplay_stream <receiver-ip> [--rate N] [--volume 0..100]\n"
        "       [--homepod|--mac|--atv|--ap1] [--port N] [--name TEXT] [--creds FILE]\n"
        "       [--strict] [--quiet]\n"
        "reads s16le stereo PCM from stdin and streams it (ctrl-c to stop)\n");
}
} // namespace

int main(int argc, char** argv) {
    std::string host, name = "airplay-stream", credsPath;
    uint32_t rate = 44100;
    uint16_t port = 7000;
    double volume = 50.0;
    bool strict = false, quiet = false;
    auto auth = RaopDeviceInfo::Auth::HapPin;
    bool airplay2 = true;
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&](const char* what) -> const char* {
            if (i + 1 >= argc) { std::fprintf(stderr, "%s needs a value\n", what); std::exit(2); }
            return argv[++i];
        };
        if      (a == "--atv")  { auth = RaopDeviceInfo::Auth::HapPin; airplay2 = true; }
        else if (a == "--mac" || a == "--homepod") { auth = RaopDeviceInfo::Auth::HapTransient; airplay2 = true; }
        else if (a == "--ap1")  { auth = RaopDeviceInfo::Auth::None; airplay2 = false; }
        else if (a == "--rate") rate = uint32_t(std::atoi(next("--rate")));
        else if (a == "--port") port = uint16_t(std::atoi(next("--port")));
        else if (a == "--name") name = next("--name");
        else if (a == "--volume") volume = std::atof(next("--volume"));
        else if (a == "--creds") credsPath = next("--creds");
        else if (a == "--strict") strict = true;
        else if (a == "--quiet") quiet = true;
        else if (a == "--help" || a == "-h") { usage(); return 0; }
        else if (!a.empty() && a[0] == '-') { std::fprintf(stderr, "unknown option %s\n", a.c_str()); usage(); return 2; }
        else if (host.empty()) host = a;
        else { usage(); return 2; }
    }
    if (host.empty()) { usage(); return 2; }
    if (credsPath.empty()) credsPath = "airplay_creds_" + host + ".json";

    std::signal(SIGINT, onSignal);
#ifdef SIGTERM
    std::signal(SIGTERM, onSignal);
#endif

    RaopLoop loop;
    RingBuffer<int16_t> ring(1u << 19);
    std::atomic<bool> done{false};
    std::atomic<bool> live{false};
    int exitCode = 0;
    RaopSender* senderPtr = nullptr;
    RaopEvents events;
    events.launched = [&](bool ok, const std::string& err) {
        if (ok) { live = true; std::printf(">> streaming, ctrl-c to stop\n"); std::fflush(stdout); return; }
        std::fprintf(stderr, ">> launch failed: %s\n", err.c_str());
        exitCode = 1; done = true;
    };
    events.closed = [&] {
        if (!done.load()) std::printf(">> session closed by the receiver\n");
        exitCode = 1; done = true;
    };
    events.pinRequired = [&](const std::string& device) {
        std::printf(">> %s is showing a pin. type the 4 digits and press enter: ", device.c_str());
        std::fflush(stdout);
        std::string line, pin;
        std::getline(std::cin, line);
        for (const char c : line) if (c >= '0' && c <= '9') pin += c;
        if (senderPtr) senderPtr->submitPin(pin);
    };
    events.credentialsObtained = [&](const std::string&, const std::string& json) {
        if (writeFile(credsPath, json)) std::printf(">> credentials saved to %s\n", credsPath.c_str());
    };
    RaopLogSink log;
    if (!quiet) {
        log = [](RaopLogLevel level, const std::string& line) {
            struct timespec ts;
            clock_gettime(CLOCK_REALTIME, &ts);
            struct tm tmv;
            localtime_r(&ts.tv_sec, &tmv);
            char buf[16];
            std::strftime(buf, sizeof(buf), "%H:%M:%S", &tmv);
            std::fprintf(stderr, "[%s.%03ld] [%s] %s\n", buf, ts.tv_nsec / 1000000,
                         level == RaopLogLevel::Warn ? "warn" : "info", line.c_str());
        };
    }
    RaopSender sender(loop, std::move(events), std::move(log));
    senderPtr = &sender;
    sender.setInputFormat(rate);
    sender.attachRing(&ring);
    sender.setVolume(volume);
    sender.setStrictReceiverAuth(strict);
    RaopIdentity identity;
    identity.name = "airplay-stream";
    sender.setIdentity(identity);
    sender.setNowPlaying("airplay-stream", "airplay2-sender-cpp", "live audio");
    sender.setAuth(auth, airplay2, host, readFile(credsPath), std::string());

    // producer: continuous s16le stereo PCM from stdin -> ring, with backpressure.
    std::thread producer([&] {
        std::vector<int16_t> stage(16384);
        std::vector<uint8_t> rbuf(32768);
        uint8_t carry = 0; bool haveCarry = false;
        auto push = [&](const int16_t* s, size_t cnt) {
            size_t off = 0;
            while (off < cnt && !done.load()) {
                const size_t a = ring.availableWrite();
                if (a == 0) { std::this_thread::sleep_for(std::chrono::milliseconds(2)); continue; }
                const size_t t = std::min(a, cnt - off);
                ring.tryPush(std::span<const int16_t>(s + off, t));
                off += t;
            }
        };
        while (!done.load()) {
            const ssize_t n = ::read(0, rbuf.data(), rbuf.size());
            if (n < 0) { if (errno == EINTR) continue; std::fprintf(stderr, ">> read error\n"); break; }
            if (n == 0) {
                std::fprintf(stderr, ">> input stream ended, draining...\n");
                std::this_thread::sleep_for(std::chrono::seconds(5));
                done = true; break;
            }
            size_t off = 0, si = 0;
            const size_t m = size_t(n);
            if (haveCarry && m > 0) {
                int16_t v; uint8_t b[2] = { carry, rbuf[0] };
                std::memcpy(&v, b, 2);
                stage[si++] = v; off = 1; haveCarry = false;
            }
            const size_t nsamp = (m - off) / 2;
            if (nsamp) { std::memcpy(stage.data() + si, rbuf.data() + off, nsamp * 2); si += nsamp; }
            if ((m - off) & 1) { carry = rbuf[m - 1]; haveCarry = true; }
            if (si) push(stage.data(), si);
        }
    });

    std::printf("connecting to %s:%u (%s, %s), input %u Hz s16le stereo...\n", host.c_str(), port, name.c_str(),
                auth == RaopDeviceInfo::Auth::HapPin ? "apple tv, hap pin"
              : auth == RaopDeviceInfo::Auth::HapTransient ? "mac/homepod, transient" : "airplay 1",
                unsigned(rate));
    sender.start(host, port, name);
    std::vector<int16_t> scratch(1 << 17);
    auto dropTo = [&](size_t target) {
        size_t avail = ring.availableRead();
        if (avail <= target) return;
        size_t drop = avail - target;
        while (drop > 0) {
            const size_t n = std::min(drop, scratch.size());
            if (!ring.tryPop(std::span<int16_t>(scratch.data(), n))) break;
            drop -= n;
        }
    };
    int shaveLogs = 0;
    while (!done.load() && !g_interrupted.load()) {
        loop.pump(sender);
        // Latency management. The input arrives in ~1 s bursts (scrcpy flushes
        // its recording once a second), so a ring level oscillating up to
        // ~1.3 s is normal — only real *accumulation* is added delay:
        //  - before the stream is live: keep the ring at ~0.25 s, so the
        //    connect phase never builds a multi-second backlog (this is what
        //    removed the 5-10 s cast delay, with no mid-stream jumps);
        //  - when live: shave 10 ms splices only above 1.6 s (absorbs the
        //    phone-vs-host clock drift; splices < ~15 ms are inaudible).
        const size_t avail = ring.availableRead();
        const size_t steadyHi = size_t(rate) * 2 * 8 / 5;      // 1.6 s
        const size_t slice    = size_t(rate) * 2 * 10 / 1000;  // 10 ms
        if (!live.load()) {
            dropTo(size_t(rate) / 2);                          // 0.25 s
        } else if (avail > steadyHi) {
            const size_t drop = std::min(avail - steadyHi, slice);
            if (ring.tryPop(std::span<int16_t>(scratch.data(), drop))) {
                if (shaveLogs++ < 60)
                    std::fprintf(stderr, ">> drift shave: %.0f ms\n",
                                 1000.0 * drop / (rate * 2.0));
            }
        }
        static auto lastDepth = std::chrono::steady_clock::now();
        const auto nowT = std::chrono::steady_clock::now();
        if (nowT - lastDepth > std::chrono::seconds(5)) {
            lastDepth = nowT;
            std::fprintf(stderr, ">> pipeline depth: %.2f s\n",
                         double(ring.availableRead()) / (rate * 2.0));
        }
    }
    if (g_interrupted.load()) std::printf("\n>> stopping\n");
    sender.stop();
    done = true;
    producer.join();
    return exitCode;
}
