// JNI bridge: AudioPlaybackCapture PCM -> patched airplay2-sender-cpp -> AirPlay receiver.
// Mirrors example/airplay_stream.cpp from the sender repo: the same ring,
// the same latency management, but fed by nativeWrite() instead of stdin.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <span>
#include <string>
#include <thread>
#include <vector>

#include "raop_loop.h"
#include "raop_sender.h"
#include "ring_buffer.h"

using namespace fxchain;

namespace {

RaopLoop* gLoop = nullptr;
RaopSender* gSender = nullptr;
RingBuffer<int16_t>* gRing = nullptr;
std::thread* gThread = nullptr;
std::atomic<bool> gLive{false};
std::atomic<bool> gDone{false};
std::atomic<bool> gStopped{false};
std::atomic<int> gWriters{0};
std::atomic<int> gPendingVolume{-1};
uint32_t gRate = 44100;

void pumpThread() {
    std::vector<int16_t> scratch(1 << 17);
    while (!gDone.load()) {
        gLoop->pump(*gSender);
        // remote (receiver) volume changes arrive from the UI thread
        const int pv = gPendingVolume.exchange(-1);
        if (pv >= 0 && gSender) gSender->setVolume(double(pv));
        // Same latency management as the Linux bridge (see docs/DEBUGGING.md):
        //  - before the stream is live: keep the ring at ~0.25 s so the
        //    connect phase never builds a backlog;
        //  - when live: shave 10 ms splices only above 1.6 s (drift absorber).
        const size_t avail = gRing->availableRead();
        const size_t steadyHi = size_t(gRate) * 2 * 8 / 5;
        const size_t slice = size_t(gRate) * 2 * 10 / 1000;
        if (!gLive.load()) {
            const size_t target = size_t(gRate) / 2;
            if (avail > target) {
                size_t drop = avail - target;
                while (drop > 0) {
                    const size_t n = std::min(drop, scratch.size());
                    if (!gRing->tryPop(std::span<int16_t>(scratch.data(), n))) break;
                    drop -= n;
                }
            }
        } else if (avail > steadyHi) {
            const size_t drop = std::min(avail - steadyHi, slice);
            gRing->tryPop(std::span<int16_t>(scratch.data(), drop));
        }
    }
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_castbridge_CastService_nativeStart(JNIEnv* env, jobject, jstring jhost, jint rate) {
    if (gSender) return -1;  // also the second-session guard: no re-init while live
    // Reset session state from a previous stop so stop->start works in the
    // same process (the service is stopped and restarted, not killed).
    gStopped = false;
    gDone = false;
    gLive = false;
    gPendingVolume = -1;
    const char* h = env->GetStringUTFChars(jhost, nullptr);
    std::string host = h ? h : "";
    env->ReleaseStringUTFChars(jhost, h);
    if (host.empty()) return -2;
    gRate = uint32_t(rate);

    RaopEvents events;
    events.launched = [](bool ok, const std::string& err) {
        if (ok) {
            gLive = true;
            __android_log_print(ANDROID_LOG_INFO, "raop", "streaming");
        } else {
            __android_log_print(ANDROID_LOG_ERROR, "raop", "launch failed: %s", err.c_str());
            gDone = true;
        }
    };
    events.closed = [] {
        __android_log_print(ANDROID_LOG_WARN, "raop", "session closed by receiver");
        gDone = true;
    };
    RaopLogSink log = [](RaopLogLevel level, const std::string& line) {
        __android_log_print(level == RaopLogLevel::Warn ? ANDROID_LOG_WARN : ANDROID_LOG_INFO,
                            "raop", "%s", line.c_str());
    };

    gLoop = new RaopLoop();
    gRing = new RingBuffer<int16_t>(1u << 19);
    gSender = new RaopSender(*gLoop, std::move(events), std::move(log));
    gSender->setInputFormat(gRate);
    gSender->attachRing(gRing);
    gSender->setVolume(40.0);
    RaopIdentity identity;
    identity.name = "Android cast";
    gSender->setIdentity(identity);
    gSender->setNowPlaying("Android", "CastBridge", "system audio");
    gSender->setAuth(RaopDeviceInfo::Auth::HapTransient, true, host,
                     std::string(), std::string());
    gSender->start(host, 7000, "Android cast");
    gThread = new std::thread(pumpThread);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_castbridge_CastService_nativeWrite(JNIEnv* env, jobject, jshortArray jpcm, jint samples) {
    if (samples <= 0) return;
    // Rank against nativeStop(): a writer that arrived first finishes its
    // copy; one that arrived after stop bails instead of touching a ring
    // stop() is about to delete. The count also bounds nativeStop's wait.
    if (gStopped.load() || !gRing) return;
    gWriters.fetch_add(1);
    if (gStopped.load() || !gRing) {           // re-check now that we count
        gWriters.fetch_sub(1);
        return;
    }
    jshort* p = env->GetShortArrayElements(jpcm, nullptr);
    if (!p) {
        gWriters.fetch_sub(1);
        return;
    }
    size_t off = 0;
    const size_t cnt = size_t(samples);
    while (off < cnt && !gDone.load() && !gStopped.load()) {
        const size_t a = gRing->availableWrite();
        if (a == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
            continue;
        }
        const size_t t = std::min(a, cnt - off);
        gRing->tryPush(std::span<const int16_t>(p + off, t));
        off += t;
    }
    env->ReleaseShortArrayElements(jpcm, p, JNI_ABORT);
    gWriters.fetch_sub(1);
}

extern "C" JNIEXPORT void JNICALL
Java_com_castbridge_CastService_nativeSetVolume(JNIEnv*, jobject, jint pct) {
    gPendingVolume.store(int(pct));
}

extern "C" JNIEXPORT void JNICALL
Java_com_castbridge_CastService_nativeStop(JNIEnv*, jobject) {
    gStopped = true;                       // refuse new writers first
    gDone = true;
    if (gThread && gThread->joinable()) gThread->join();  // pump stops first
    // Let a write already in flight finish before the ring disappears.
    while (gWriters.load() > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    if (gSender) gSender->stop();
    delete gThread; gThread = nullptr;
    delete gSender; gSender = nullptr;
    delete gRing; gRing = nullptr;
    delete gLoop; gLoop = nullptr;
    gLive = false;
    gStopped = false;
}
