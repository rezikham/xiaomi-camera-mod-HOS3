// libcameraopt_stub.so stub for Aurora
// Provides AlgoControllerAdapter used by mileicafilter.so and legendST.so
// Stock Aurora does NOT have this library; this stub enables plugin loading.

#include <cstdint>
#include <string>
#include <mutex>

class AlgoControllerAdapter {
public:
    AlgoControllerAdapter(const std::string& name) : name_(name) {}
    ~AlgoControllerAdapter() = default;

    uint32_t getFlag() {
        std::lock_guard<std::mutex> lock(mutex_);
        return flags_;
    }

    void setFlag(uint32_t flag) {
        std::lock_guard<std::mutex> lock(mutex_);
        flags_ = flag;
    }

private:
    std::string name_;
    uint32_t flags_ = 0;
    std::mutex mutex_;
};

// Exported C++ symbols that plugins expect
extern "C" {

// _ZN9cameraopt21AlgoControllerAdapterC1ERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE
void* _ZN9cameraopt21AlgoControllerAdapterC1ERKNSt3__112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE(
    void* self, const char* name) {
    // Placement new: construct AlgoControllerAdapter at self
    return new (self) AlgoControllerAdapter(name ? name : "");
}

// _ZN9cameraopt21AlgoControllerAdapterD1Ev
void _ZN9cameraopt21AlgoControllerAdapterD1Ev(void* self) {
    if (self) {
        static_cast<AlgoControllerAdapter*>(self)->~AlgoControllerAdapter();
    }
}

// _ZN9cameraopt21AlgoControllerAdapter7getFlagEv
uint32_t _ZN9cameraopt21AlgoControllerAdapter7getFlagEv(void* self) {
    if (!self) return 0;
    return static_cast<AlgoControllerAdapter*>(self)->getFlag();
}

} // extern "C"