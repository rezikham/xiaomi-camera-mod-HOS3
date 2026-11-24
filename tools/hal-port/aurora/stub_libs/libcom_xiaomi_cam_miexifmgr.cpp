// libcom.xiaomi.cam.miexifmgr.so stub for Aurora
// Provides EXIF/maker-note support used by mileicafilter.so and legendST.so
// Stock Aurora does NOT have this library; this stub enables plugin loading.

#include <cstdint>
#include <cstring>
#include <string>
#include <mutex>
#include <unordered_map>

class ExifManager {
public:
    static ExifManager* getInstance() {
        static ExifManager instance;
        return &instance;
    }

    // Set EXIF tag value
    void setTag(uint32_t tagId, const void* value, uint32_t size) {
        std::lock_guard<std::mutex> lock(mutex_);
        tags_[tagId] = std::string(static_cast<const char*>(value), size);
    }

    // Get EXIF tag value
    bool getTag(uint32_t tagId, void* buffer, uint32_t bufferSize) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = tags_.find(tagId);
        if (it == tags_.end()) return false;
        uint32_t copySize = std::min(bufferSize, static_cast<uint32_t>(it->second.size()));
        memcpy(buffer, it->second.data(), copySize);
        return true;
    }

    // Check if tag exists
    bool hasTag(uint32_t tagId) {
        std::lock_guard<std::mutex> lock(mutex_);
        return tags_.find(tagId) != tags_.end();
    }

private:
    ExifManager() = default;
    ~ExifManager() = default;
    ExifManager(const ExifManager&) = delete;
    ExifManager& operator=(const ExifManager&) = delete;

    std::mutex mutex_;
    std::unordered_map<uint32_t, std::string> tags_;
};

// Exported C symbols that plugins expect
extern "C" {

// Placeholder exports - plugins may reference these
void* _ZN21MiexifmgrManager11getInstanceEv() {
    return ExifManager::getInstance();
}

} // extern "C"