#include "fast/renderer3ds/pipeline_cache_flusher.h"

#include "fast/renderer/preparation_host.h"

#include <cstdio>
#include <exception>
#include <string>
#include <utility>

namespace Fast::Renderer3ds {

bool PipelineCacheFlushDue(uint64_t createdSinceFlush, double secondsSinceFlush,
                           const PipelineCacheFlushPolicy& policy) {
    if (createdSinceFlush == 0 || createdSinceFlush < policy.MinimumNewPipelines) return false;
    return secondsSinceFlush >= policy.MinimumIntervalSeconds;
}

PipelineCacheFlusher::~PipelineCacheFlusher() { Stop(); }

void PipelineCacheFlusher::Start(std::filesystem::path path, VulkanPipelineCacheHeader identity,
                                 uint64_t maximumBytes, Snapshot snapshot, PipelineCacheFlushPolicy policy) {
    Stop();
    if (path.empty() || !snapshot || maximumBytes == 0) return;
    mPath = std::move(path);
    mIdentity = identity;
    mMaximumBytes = maximumBytes;
    mSnapshot = std::move(snapshot);
    mPolicy = policy;
    {
        std::lock_guard lock(mMutex);
        mStopping = false;
        mFlushedTotal = mCreatedTotal.load();
        mLastFlush = std::chrono::steady_clock::now();
    }
    mThread = std::thread([this] { Worker(); });
}

void PipelineCacheFlusher::Notice(uint64_t createdTotal) {
    // Notice is called every frame. Only take the wait mutex on a change,
    // so the worker cannot miss a notification while it goes to sleep.
    if (mCreatedTotal.load(std::memory_order_relaxed) == createdTotal) return;
    {
        std::lock_guard lock(mMutex);
        mCreatedTotal.store(createdTotal, std::memory_order_relaxed);
    }
    mWake.notify_one();
}

void PipelineCacheFlusher::Stop() {
    if (!mThread.joinable()) return;
    {
        std::lock_guard lock(mMutex);
        mStopping = true;
    }
    mWake.notify_all();
    mThread.join();
}

PipelineCacheFlusher::Statistics PipelineCacheFlusher::Stats() const {
    std::lock_guard lock(mMutex);
    return mStatistics;
}

void PipelineCacheFlusher::Worker() {
    Renderer::LowerPreparationThreadPriority();
    using Clock = std::chrono::steady_clock;
    const auto interval = std::chrono::ceil<Clock::duration>(
        std::chrono::duration<double>(mPolicy.MinimumIntervalSeconds));
    const auto hasNewPipelines = [this] {
        const uint64_t created = mCreatedTotal.load(std::memory_order_relaxed);
        const uint64_t delta = created >= mFlushedTotal ? created - mFlushedTotal : created;
        return delta > 0 && delta >= mPolicy.MinimumNewPipelines;
    };
    std::unique_lock lock(mMutex);
    for (;;) {
        // No timer is needed while the cache is warm and nobody creates pipelines.
        mWake.wait(lock, [&] { return mStopping || hasNewPipelines(); });
        if (mStopping) return;
        if (mWake.wait_until(lock, mLastFlush + interval, [this] { return mStopping; })) return;
        if (!hasNewPipelines()) continue;
        const uint64_t created = mCreatedTotal.load(std::memory_order_relaxed);
        lock.unlock();
        FlushOnce(created);
        lock.lock();
        mLastFlush = Clock::now();
        // Failed or skipped snapshots are retried only after more creations.
        mFlushedTotal = created;
    }
}

bool PipelineCacheFlusher::FlushOnce(uint64_t created) {
    const auto snapshotStarted = std::chrono::steady_clock::now();
    std::vector<uint8_t> data;
    try {
        data = mSnapshot();
    } catch (const std::exception& exception) {
        std::lock_guard lock(mMutex);
        ++mStatistics.Failures;
        std::fprintf(stderr, "TRIAEVUM_NRI_PIPELINE_CACHE_FLUSH result=failed created=%llu error=%s\n",
                     static_cast<unsigned long long>(created), exception.what());
        return false;
    }
    if (data.empty() || data.size() > mMaximumBytes) {
        std::lock_guard lock(mMutex);
        ++mStatistics.Skipped;
        return false;
    }
    const auto storeStarted = std::chrono::steady_clock::now();
    std::string error;
    const bool stored = StorePipelineCacheData(mPath, mIdentity, data, &error, mMaximumBytes);
    const auto storedAt = std::chrono::steady_clock::now();
    using Milliseconds = std::chrono::duration<double, std::milli>;
    std::lock_guard lock(mMutex);
    if (stored) {
        ++mStatistics.Flushes;
        mStatistics.FlushedBytes += data.size();
        std::fprintf(stderr,
                     "TRIAEVUM_NRI_PIPELINE_CACHE_FLUSH result=stored bytes=%zu created=%llu snapshot_ms=%.1f store_ms=%.1f\n",
                     data.size(), static_cast<unsigned long long>(created),
                     Milliseconds(storeStarted - snapshotStarted).count(),
                     Milliseconds(storedAt - storeStarted).count());
    } else {
        ++mStatistics.Failures;
        std::fprintf(stderr, "TRIAEVUM_NRI_PIPELINE_CACHE_FLUSH result=failed bytes=%zu created=%llu error=%s\n",
                     data.size(), static_cast<unsigned long long>(created), error.c_str());
    }
    return stored;
}

}  // namespace Fast::Renderer3ds
