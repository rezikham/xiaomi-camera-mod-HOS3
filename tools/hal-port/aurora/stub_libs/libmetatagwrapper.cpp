// libmetatagwrapper.so stub for Aurora
// Provides MetaTagWrapper class used by mileicafilter.so and legendST.so
// Stock Aurora does NOT have this library; this stub enables plugin loading.

#include <cstdint>
#include <cstring>
#include <unordered_map>
#include <string>
#include <mutex>

#define META_TAG_INDEX int

class MetaTagWrapper {
public:
    static MetaTagWrapper* getInstance() {
        static MetaTagWrapper instance;
        return &instance;
    }

    // operator() returns tag location pointer for a given tag index
    uint8_t* operator()(META_TAG_INDEX tagIndex) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = tags_.find(tagIndex);
        if (it == tags_.end()) {
            // Allocate new tag storage
            tags_[tagIndex] = std::make_unique<uint8_t[]>(4);
            it = tags_.find(tagIndex);
            memset(it->second.get(), 0, 4);
        }
        return it->second.get();
    }

    // Set a tag value (byte)
    void setTag(META_TAG_INDEX tagIndex, uint8_t value) {
        uint8_t* ptr = (*this)(tagIndex);
        if (ptr) *ptr = value;
    }

    // Get a tag value (byte)
    uint8_t getTag(META_TAG_INDEX tagIndex) {
        uint8_t* ptr = (*this)(tagIndex);
        return ptr ? *ptr : 0;
    }

private:
    MetaTagWrapper() = default;
    ~MetaTagWrapper() = default;
    MetaTagWrapper(const MetaTagWrapper&) = delete;
    MetaTagWrapper& operator=(const MetaTagWrapper&) = delete;

    std::mutex mutex_;
    std::unordered_map<META_TAG_INDEX, std::unique_ptr<uint8_t[]>> tags_;
};

// Exported C symbols that plugins expect
extern "C" {

// _ZN14MetaTagWrapper11getInstanceEv
MetaTagWrapper* _ZN14MetaTagWrapper11getInstanceEv() {
    return MetaTagWrapper::getInstance();
}

// _ZNK14MetaTagWrapperclERK14META_TAG_INDEX
uint8_t* _ZNK14MetaTagWrapperclERK14META_TAG_INDEX(META_TAG_INDEX tagIndex) {
    return (*MetaTagWrapper::getInstance())(tagIndex);
}

} // extern "C"