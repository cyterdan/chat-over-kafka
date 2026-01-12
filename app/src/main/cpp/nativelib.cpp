#include <jni.h>
#include <rdkafka.h>
#include <string>
#include <android/log.h>
#include <vector>
#include <cstdint>
#include <memory>
#include <atomic>

// --- C++ Best Practices & Helpers ---

// Use a C++ constant instead of #define
static const char* const LOG_TAG = "librdkafka";

/**
 * RAII (Resource Acquisition Is Initialization) wrapper for JNI strings.
 * This class ensures that ReleaseStringUTFChars is always called, even if
 * an exception occurs or the function returns early.
 */
class JniStringWrapper {
public:
    JniStringWrapper(JNIEnv* env, jstring jstr) : m_env(env), m_jstr(jstr) {
        if (m_jstr) {
            m_cstr = m_env->GetStringUTFChars(m_jstr, nullptr);
        }
    }

    ~JniStringWrapper() {
        if (m_cstr) {
            m_env->ReleaseStringUTFChars(m_jstr, m_cstr);
        }
    }

    // Disallow copying to prevent double-free errors
    JniStringWrapper(const JniStringWrapper&) = delete;
    JniStringWrapper& operator=(const JniStringWrapper&) = delete;

    const char* get() const { return m_cstr; }
    operator const char*() const { return m_cstr; }
    size_t length() const { return m_cstr ? strlen(m_cstr) : 0; }

private:
    JNIEnv* m_env;
    jstring m_jstr;
    const char* m_cstr = nullptr;
};

// Helper to throw exceptions in Java
void throwJavaException(JNIEnv *env, const char *msg) {
    jclass exc = env->FindClass("java/lang/RuntimeException");
    if (exc != nullptr) {
        env->ThrowNew(exc, msg);
    }
    env->DeleteLocalRef(exc);
}

// --- JNI Implementations ---

extern "C" {

void kafka_log_callback(const rd_kafka_t* /*rk*/, int level, const char* fac, const char* buf) {
    int android_level;
    // Map librdkafka log levels (based on syslog priorities) to Android log levels.
    // Lower syslog level number means higher severity.
    switch (level) {
        case 0: // LOG_EMERG
        case 1: // LOG_ALERT
        case 2: // LOG_CRIT
        case 3: // LOG_ERR
            android_level = ANDROID_LOG_ERROR;
            break;
        case 4: // LOG_WARNING
            android_level = ANDROID_LOG_WARN;
            break;
        case 5: // LOG_NOTICE
        case 6: // LOG_INFO
            android_level = ANDROID_LOG_INFO;
            break;
        case 7: // LOG_DEBUG
        default:
            android_level = ANDROID_LOG_DEBUG;
            break;
    }
    __android_log_print(android_level, LOG_TAG, "[%s] %s", fac, buf);
}

JNIEXPORT jstring JNICALL
Java_org_github_cyterdan_chat_1over_1kafka_RdKafka_version(
        JNIEnv *env,
        jobject /* this */) {
    return env->NewStringUTF(rd_kafka_version_str());
}

JNIEXPORT jlong JNICALL
Java_org_github_cyterdan_chat_1over_1kafka_RdKafka_createConsumerMTLS(
        JNIEnv* env,
        jobject /* this */,
        jstring jbrokers,
        jstring jgroupId,
        jstring jcaCertPath,
        jstring jclientCertPath,
        jstring jclientKeyPath,
        jstring joffsetStrategy) {

    if (!jbrokers) {
        throwJavaException(env, "Brokers cannot be null");
        return 0;
    }
    if (!jgroupId) {
        throwJavaException(env, "Group ID cannot be null");
        return 0;
    }
    if (!jcaCertPath || !jclientCertPath || !jclientKeyPath) {
        throwJavaException(env, "Certificate paths cannot be null");
        return 0;
    }

    const char* brokers = env->GetStringUTFChars(jbrokers, nullptr);
    const char* groupId = env->GetStringUTFChars(jgroupId, nullptr);
    const char* caCertPath = env->GetStringUTFChars(jcaCertPath, nullptr);
    const char* clientCertPath = env->GetStringUTFChars(jclientCertPath, nullptr);
    const char* clientKeyPath = env->GetStringUTFChars(jclientKeyPath, nullptr);
    const char* offsetStrategy = joffsetStrategy ? env->GetStringUTFChars(joffsetStrategy, nullptr) : "latest";

    if (!brokers || !groupId || !caCertPath || !clientCertPath || !clientKeyPath) {
        if (brokers) env->ReleaseStringUTFChars(jbrokers, brokers);
        if (groupId) env->ReleaseStringUTFChars(jgroupId, groupId);
        if (caCertPath) env->ReleaseStringUTFChars(jcaCertPath, caCertPath);
        if (clientCertPath) env->ReleaseStringUTFChars(jclientCertPath, clientCertPath);
        if (clientKeyPath) env->ReleaseStringUTFChars(jclientKeyPath, clientKeyPath);
        if (offsetStrategy && joffsetStrategy) env->ReleaseStringUTFChars(joffsetStrategy, offsetStrategy);
        throwJavaException(env, "Failed to get strings from JNI");
        return 0;
    }

    char errstr[512];
    rd_kafka_conf_t* conf = rd_kafka_conf_new();

    // Set bootstrap servers
    if (rd_kafka_conf_set(conf, "bootstrap.servers", brokers, errstr, sizeof(errstr)) != RD_KAFKA_CONF_OK) {
        env->ReleaseStringUTFChars(jbrokers, brokers);
        env->ReleaseStringUTFChars(jgroupId, groupId);
        env->ReleaseStringUTFChars(jcaCertPath, caCertPath);
        env->ReleaseStringUTFChars(jclientCertPath, clientCertPath);
        env->ReleaseStringUTFChars(jclientKeyPath, clientKeyPath);
        if (offsetStrategy && joffsetStrategy) env->ReleaseStringUTFChars(joffsetStrategy, offsetStrategy);
        rd_kafka_conf_destroy(conf);
        throwJavaException(env, errstr);
        return 0;
    }

    // Set group ID
    if (rd_kafka_conf_set(conf, "group.id", groupId, errstr, sizeof(errstr)) != RD_KAFKA_CONF_OK) {
        env->ReleaseStringUTFChars(jbrokers, brokers);
        env->ReleaseStringUTFChars(jgroupId, groupId);
        env->ReleaseStringUTFChars(jcaCertPath, caCertPath);
        env->ReleaseStringUTFChars(jclientCertPath, clientCertPath);
        env->ReleaseStringUTFChars(jclientKeyPath, clientKeyPath);
        if (offsetStrategy && joffsetStrategy) env->ReleaseStringUTFChars(joffsetStrategy, offsetStrategy);
        rd_kafka_conf_destroy(conf);
        throwJavaException(env, errstr);
        return 0;
    }

    // Set security protocol to SSL
    if (rd_kafka_conf_set(conf, "security.protocol", "SSL", errstr, sizeof(errstr)) != RD_KAFKA_CONF_OK) {
        env->ReleaseStringUTFChars(jbrokers, brokers);
        env->ReleaseStringUTFChars(jgroupId, groupId);
        env->ReleaseStringUTFChars(jcaCertPath, caCertPath);
        env->ReleaseStringUTFChars(jclientCertPath, clientCertPath);
        env->ReleaseStringUTFChars(jclientKeyPath, clientKeyPath);
        if (offsetStrategy && joffsetStrategy) env->ReleaseStringUTFChars(joffsetStrategy, offsetStrategy);
        rd_kafka_conf_destroy(conf);
        throwJavaException(env, errstr);
        return 0;
    }

    // Set CA certificate
    if (rd_kafka_conf_set(conf, "ssl.ca.location", caCertPath, errstr, sizeof(errstr)) != RD_KAFKA_CONF_OK) {
        env->ReleaseStringUTFChars(jbrokers, brokers);
        env->ReleaseStringUTFChars(jgroupId, groupId);
        env->ReleaseStringUTFChars(jcaCertPath, caCertPath);
        env->ReleaseStringUTFChars(jclientCertPath, clientCertPath);
        env->ReleaseStringUTFChars(jclientKeyPath, clientKeyPath);
        if (offsetStrategy && joffsetStrategy) env->ReleaseStringUTFChars(joffsetStrategy, offsetStrategy);
        rd_kafka_conf_destroy(conf);
        throwJavaException(env, errstr);
        return 0;
    }

    // Set client certificate
    if (rd_kafka_conf_set(conf, "ssl.certificate.location", clientCertPath, errstr, sizeof(errstr)) != RD_KAFKA_CONF_OK) {
        env->ReleaseStringUTFChars(jbrokers, brokers);
        env->ReleaseStringUTFChars(jgroupId, groupId);
        env->ReleaseStringUTFChars(jcaCertPath, caCertPath);
        env->ReleaseStringUTFChars(jclientCertPath, clientCertPath);
        env->ReleaseStringUTFChars(jclientKeyPath, clientKeyPath);
        if (offsetStrategy && joffsetStrategy) env->ReleaseStringUTFChars(joffsetStrategy, offsetStrategy);
        rd_kafka_conf_destroy(conf);
        throwJavaException(env, errstr);
        return 0;
    }

    // Set client key
    if (rd_kafka_conf_set(conf, "ssl.key.location", clientKeyPath, errstr, sizeof(errstr)) != RD_KAFKA_CONF_OK) {
        env->ReleaseStringUTFChars(jbrokers, brokers);
        env->ReleaseStringUTFChars(jgroupId, groupId);
        env->ReleaseStringUTFChars(jcaCertPath, caCertPath);
        env->ReleaseStringUTFChars(jclientCertPath, clientCertPath);
        env->ReleaseStringUTFChars(jclientKeyPath, clientKeyPath);
        if (offsetStrategy && joffsetStrategy) env->ReleaseStringUTFChars(joffsetStrategy, offsetStrategy);
        rd_kafka_conf_destroy(conf);
        throwJavaException(env, errstr);
        return 0;
    }

    // Set auto.offset.reset based on provided strategy
    if (rd_kafka_conf_set(conf, "auto.offset.reset", offsetStrategy, errstr, sizeof(errstr)) != RD_KAFKA_CONF_OK) {
        env->ReleaseStringUTFChars(jbrokers, brokers);
        env->ReleaseStringUTFChars(jgroupId, groupId);
        env->ReleaseStringUTFChars(jcaCertPath, caCertPath);
        env->ReleaseStringUTFChars(jclientCertPath, clientCertPath);
        env->ReleaseStringUTFChars(jclientKeyPath, clientKeyPath);
        if (offsetStrategy && joffsetStrategy) env->ReleaseStringUTFChars(joffsetStrategy, offsetStrategy);
        rd_kafka_conf_destroy(conf);
        throwJavaException(env, errstr);
        return 0;
    }

    // Create consumer
    rd_kafka_t* consumer = rd_kafka_new(RD_KAFKA_CONSUMER, conf, errstr, sizeof(errstr));

    env->ReleaseStringUTFChars(jbrokers, brokers);
    env->ReleaseStringUTFChars(jgroupId, groupId);
    env->ReleaseStringUTFChars(jcaCertPath, caCertPath);
    env->ReleaseStringUTFChars(jclientCertPath, clientCertPath);
    env->ReleaseStringUTFChars(jclientKeyPath, clientKeyPath);
    if (offsetStrategy && joffsetStrategy) env->ReleaseStringUTFChars(joffsetStrategy, offsetStrategy);

    if (!consumer) {
        rd_kafka_conf_destroy(conf);
        throwJavaException(env, errstr);
        return 0;
    }

    return reinterpret_cast<jlong>(consumer);
}


struct DeliveryState {
    std::mutex mtx;
    std::condition_variable cv;
    std::atomic<bool> done{false};
    rd_kafka_resp_err_t err = RD_KAFKA_RESP_ERR_NO_ERROR;
    int32_t partition = -1;
    int64_t offset = -1;
};
static void delivery_report_cb(rd_kafka_t*,
                               const rd_kafka_message_t* msg,
                               void*) {
    auto* state = static_cast<DeliveryState*>(msg->_private);
    if (!state) return;

    {
        std::lock_guard<std::mutex> lock(state->mtx);
        state->err = msg->err;
        state->partition = msg->partition;
        state->offset = msg->offset;
    }

    state->done.store(true, std::memory_order_release);
    state->cv.notify_one();
}


JNIEXPORT jlong JNICALL
Java_org_github_cyterdan_chat_1over_1kafka_RdKafka_createProducerMTLS(
        JNIEnv *env,
        jobject /* this */,
        jstring jbrokers,
        jstring jcaCertPath,
        jstring jclientCertPath,
        jstring jclientKeyPath) {

    JniStringWrapper brokers(env, jbrokers);
    JniStringWrapper caCert(env, jcaCertPath);
    JniStringWrapper clientCert(env, jclientCertPath);
    JniStringWrapper clientKey(env, jclientKeyPath);

    char errstr[512];
    rd_kafka_conf_t *conf = rd_kafka_conf_new();
    rd_kafka_t *producer = nullptr;

    // Use a unique_ptr for the config to ensure it's cleaned up on failure
    auto conf_deleter = [](rd_kafka_conf_t *c) { rd_kafka_conf_destroy(c); };
    std::unique_ptr<rd_kafka_conf_t, decltype(conf_deleter)> conf_ptr(conf, conf_deleter);

    // --- Configuration ---
    if (rd_kafka_conf_set(conf_ptr.get(), "bootstrap.servers", brokers, errstr, sizeof(errstr)) != RD_KAFKA_CONF_OK) {
        throwJavaException(env, errstr);
        return 0;
    }
    if (rd_kafka_conf_set(conf_ptr.get(), "security.protocol", "SSL", errstr, sizeof(errstr)) != RD_KAFKA_CONF_OK) {
        throwJavaException(env, errstr);
        return 0;
    }
    if (rd_kafka_conf_set(conf_ptr.get(), "ssl.ca.location", caCert, errstr, sizeof(errstr)) != RD_KAFKA_CONF_OK) {
        throwJavaException(env, errstr);
        return 0;
    }
    if (rd_kafka_conf_set(conf_ptr.get(), "ssl.certificate.location", clientCert, errstr, sizeof(errstr)) != RD_KAFKA_CONF_OK) {
        throwJavaException(env, errstr);
        return 0;
    }
    if (rd_kafka_conf_set(conf_ptr.get(), "ssl.key.location", clientKey, errstr, sizeof(errstr)) != RD_KAFKA_CONF_OK) {
        throwJavaException(env, errstr);
        return 0;
    }

    rd_kafka_conf_set(conf_ptr.get(), "acks", "all", errstr, sizeof(errstr));
    rd_kafka_conf_set_log_cb(conf_ptr.get(), kafka_log_callback);
    rd_kafka_conf_set_dr_msg_cb(conf, delivery_report_cb);

    // --- Producer Creation ---
    // rd_kafka_new takes ownership of `conf` on success.
    // We release the unique_ptr to prevent it from being double-freed.
    producer = rd_kafka_new(RD_KAFKA_PRODUCER, conf_ptr.release(), errstr, sizeof(errstr));
    if (!producer) {
        throwJavaException(env, errstr);
        return 0;
    }

    return reinterpret_cast<jlong>(producer);
}
JNIEXPORT jobject JNICALL
Java_org_github_cyterdan_chat_1over_1kafka_RdKafka_produceMessageBytes(
        JNIEnv* env,
        jobject,
        jlong producerPtr,
        jstring jtopic,
        jbyteArray jkey,
        jbyteArray jvalue) {

    if (!producerPtr || !jtopic || !jvalue) {
        throwJavaException(env, "Invalid arguments");
        return nullptr;
    }

    auto* producer = reinterpret_cast<rd_kafka_t*>(producerPtr);

    // ---- Topic ----
    const char* topic = env->GetStringUTFChars(jtopic, nullptr);
    if (!topic) {
        throwJavaException(env, "Failed to get topic");
        return nullptr;
    }

    // ---- Value ----
    jbyte* value_bytes = env->GetByteArrayElements(jvalue, nullptr);
    jsize value_len = env->GetArrayLength(jvalue);
    if (!value_bytes) {
        env->ReleaseStringUTFChars(jtopic, topic);
        throwJavaException(env, "Failed to get value bytes");
        return nullptr;
    }

    // ---- Key (optional) ----
    jbyte* key_bytes = nullptr;
    jsize key_len = 0;
    if (jkey) {
        key_bytes = env->GetByteArrayElements(jkey, nullptr);
        key_len = env->GetArrayLength(jkey);
        if (!key_bytes) {
            env->ReleaseStringUTFChars(jtopic, topic);
            env->ReleaseByteArrayElements(jvalue, value_bytes, JNI_ABORT);
            throwJavaException(env, "Failed to get key bytes");
            return nullptr;
        }
    }

    DeliveryState state;

    // ---- Produce ----
    rd_kafka_resp_err_t err;
    if (key_bytes) {
        err = rd_kafka_producev(
                producer,
                RD_KAFKA_V_TOPIC(topic),
                RD_KAFKA_V_KEY(key_bytes, key_len),
                RD_KAFKA_V_VALUE(value_bytes, value_len),
                RD_KAFKA_V_MSGFLAGS(RD_KAFKA_MSG_F_COPY),
                RD_KAFKA_V_OPAQUE(&state),
                RD_KAFKA_V_END
        );
    } else {
        err = rd_kafka_producev(
                producer,
                RD_KAFKA_V_TOPIC(topic),
                RD_KAFKA_V_VALUE(value_bytes, value_len),
                RD_KAFKA_V_MSGFLAGS(RD_KAFKA_MSG_F_COPY),
                RD_KAFKA_V_OPAQUE(&state),
                RD_KAFKA_V_END
        );
    }

    // ---- JNI cleanup (safe due to COPY) ----
    env->ReleaseStringUTFChars(jtopic, topic);
    env->ReleaseByteArrayElements(jvalue, value_bytes, JNI_ABORT);
    if (key_bytes) {
        env->ReleaseByteArrayElements(jkey, key_bytes, JNI_ABORT);
    }

    if (err != RD_KAFKA_RESP_ERR_NO_ERROR) {
        throwJavaException(env, rd_kafka_err2str(err));
        return nullptr;
    }

    // ---- Block until delivery ----
    while (true) {
        rd_kafka_poll(producer, 100);

        std::unique_lock<std::mutex> lock(state.mtx);
        if (state.done)
            break;

        state.cv.wait_for(lock, std::chrono::milliseconds(50));
    }

    if (state.err != RD_KAFKA_RESP_ERR_NO_ERROR) {
        throwJavaException(env, rd_kafka_err2str(state.err));
        return nullptr;
    }

    // ---- Build Kotlin RecordMetadata ----
    jclass cls = env->FindClass(
            "org/github/cyterdan/chat_over_kafka/RecordMetadata");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(IJ)V");

    return env->NewObject(
            cls,
            ctor,
            state.partition,
            (jlong)state.offset
    );
}

JNIEXPORT jobject JNICALL
Java_org_github_cyterdan_chat_1over_1kafka_RdKafka_produceMessageBytesToPartition(
        JNIEnv* env,
        jobject,
        jlong producerPtr,
        jstring jtopic,
        jint jpartition,
        jbyteArray jkey,
        jbyteArray jvalue) {

    if (!producerPtr || !jtopic || !jvalue) {
        throwJavaException(env, "Invalid arguments");
        return nullptr;
    }

    auto* producer = reinterpret_cast<rd_kafka_t*>(producerPtr);

    // ---- Topic ----
    const char* topic = env->GetStringUTFChars(jtopic, nullptr);
    if (!topic) {
        throwJavaException(env, "Failed to get topic");
        return nullptr;
    }

    // ---- Value ----
    jbyte* value_bytes = env->GetByteArrayElements(jvalue, nullptr);
    jsize value_len = env->GetArrayLength(jvalue);
    if (!value_bytes) {
        env->ReleaseStringUTFChars(jtopic, topic);
        throwJavaException(env, "Failed to get value bytes");
        return nullptr;
    }

    // ---- Key (optional) ----
    jbyte* key_bytes = nullptr;
    jsize key_len = 0;
    if (jkey) {
        key_bytes = env->GetByteArrayElements(jkey, nullptr);
        key_len = env->GetArrayLength(jkey);
        if (!key_bytes) {
            env->ReleaseStringUTFChars(jtopic, topic);
            env->ReleaseByteArrayElements(jvalue, value_bytes, JNI_ABORT);
            throwJavaException(env, "Failed to get key bytes");
            return nullptr;
        }
    }

    DeliveryState state;

    // ---- Produce to specific partition ----
    rd_kafka_resp_err_t err;
    if (key_bytes) {
        err = rd_kafka_producev(
                producer,
                RD_KAFKA_V_TOPIC(topic),
                RD_KAFKA_V_PARTITION((int32_t)jpartition),
                RD_KAFKA_V_KEY(key_bytes, key_len),
                RD_KAFKA_V_VALUE(value_bytes, value_len),
                RD_KAFKA_V_MSGFLAGS(RD_KAFKA_MSG_F_COPY),
                RD_KAFKA_V_OPAQUE(&state),
                RD_KAFKA_V_END
        );
    } else {
        err = rd_kafka_producev(
                producer,
                RD_KAFKA_V_TOPIC(topic),
                RD_KAFKA_V_PARTITION((int32_t)jpartition),
                RD_KAFKA_V_VALUE(value_bytes, value_len),
                RD_KAFKA_V_MSGFLAGS(RD_KAFKA_MSG_F_COPY),
                RD_KAFKA_V_OPAQUE(&state),
                RD_KAFKA_V_END
        );
    }

    // ---- JNI cleanup (safe due to COPY) ----
    env->ReleaseStringUTFChars(jtopic, topic);
    env->ReleaseByteArrayElements(jvalue, value_bytes, JNI_ABORT);
    if (key_bytes) {
        env->ReleaseByteArrayElements(jkey, key_bytes, JNI_ABORT);
    }

    if (err != RD_KAFKA_RESP_ERR_NO_ERROR) {
        throwJavaException(env, rd_kafka_err2str(err));
        return nullptr;
    }

    // ---- Block until delivery ----
    while (true) {
        rd_kafka_poll(producer, 100);

        std::unique_lock<std::mutex> lock(state.mtx);
        if (state.done)
            break;

        state.cv.wait_for(lock, std::chrono::milliseconds(50));
    }

    if (state.err != RD_KAFKA_RESP_ERR_NO_ERROR) {
        throwJavaException(env, rd_kafka_err2str(state.err));
        return nullptr;
    }

    // ---- Build Kotlin RecordMetadata ----
    jclass cls = env->FindClass(
            "org/github/cyterdan/chat_over_kafka/RecordMetadata");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(IJ)V");

    return env->NewObject(
            cls,
            ctor,
            state.partition,
            (jlong)state.offset
    );
}

// Create a consumer
JNIEXPORT jlong JNICALL
Java_org_github_cyterdan_chat_1over_1kafka_RdKafka_createConsumer(
        JNIEnv* env,
        jobject /* this */,
        jstring jbrokers,
        jstring jgroupId) {

    if (!jbrokers) {
        throwJavaException(env, "Brokers cannot be null");
        return 0;
    }
    if (!jgroupId) {
        throwJavaException(env, "Group ID cannot be null");
        return 0;
    }

    const char* brokers = env->GetStringUTFChars(jbrokers, nullptr);
    const char* groupId = env->GetStringUTFChars(jgroupId, nullptr);

    if (!brokers || !groupId) {
        if (brokers) env->ReleaseStringUTFChars(jbrokers, brokers);
        if (groupId) env->ReleaseStringUTFChars(jgroupId, groupId);
        throwJavaException(env, "Failed to get strings from JNI");
        return 0;
    }

    char errstr[512];
    rd_kafka_conf_t* conf = rd_kafka_conf_new();

    // Set bootstrap servers
    if (rd_kafka_conf_set(conf, "bootstrap.servers", brokers, errstr, sizeof(errstr)) != RD_KAFKA_CONF_OK) {
        env->ReleaseStringUTFChars(jbrokers, brokers);
        env->ReleaseStringUTFChars(jgroupId, groupId);
        rd_kafka_conf_destroy(conf);
        throwJavaException(env, errstr);
        return 0;
    }

    // Set group ID
    if (rd_kafka_conf_set(conf, "group.id", groupId, errstr, sizeof(errstr)) != RD_KAFKA_CONF_OK) {
        env->ReleaseStringUTFChars(jbrokers, brokers);
        env->ReleaseStringUTFChars(jgroupId, groupId);
        rd_kafka_conf_destroy(conf);
        throwJavaException(env, errstr);
        return 0;
    }

    // Create consumer
    rd_kafka_t* consumer = rd_kafka_new(RD_KAFKA_CONSUMER, conf, errstr, sizeof(errstr));

    env->ReleaseStringUTFChars(jbrokers, brokers);
    env->ReleaseStringUTFChars(jgroupId, groupId);

    if (!consumer) {
        rd_kafka_conf_destroy(conf);
        throwJavaException(env, errstr);
        return 0;
    }

    return reinterpret_cast<jlong>(consumer);
}

// Subscribe to topic with offset strategy
JNIEXPORT void JNICALL
Java_org_github_cyterdan_chat_1over_1kafka_RdKafka_subscribe(
        JNIEnv* env,
        jobject /* this */,
        jlong consumerPtr,
        jstring jtopic,
        jstring joffsetStrategy) {

    if (consumerPtr == 0) {
        throwJavaException(env, "Consumer pointer is null");
        return;
    }
    if (!jtopic) {
        throwJavaException(env, "Topic cannot be null");
        return;
    }

    auto* consumer = reinterpret_cast<rd_kafka_t*>(consumerPtr);

    const char* topic = env->GetStringUTFChars(jtopic, nullptr);
    const char* offsetStrategy = joffsetStrategy ? env->GetStringUTFChars(joffsetStrategy, nullptr) : "latest";

    if (!topic) {
        if (offsetStrategy && joffsetStrategy) env->ReleaseStringUTFChars(joffsetStrategy, offsetStrategy);
        throwJavaException(env, "Failed to get topic string from JNI");
        return;
    }

    // Set auto.offset.reset based on strategy
    char errstr[512];
    rd_kafka_conf_t* conf = rd_kafka_conf_new();

    if (rd_kafka_conf_set(conf, "auto.offset.reset", offsetStrategy, errstr, sizeof(errstr)) != RD_KAFKA_CONF_OK) {
        env->ReleaseStringUTFChars(jtopic, topic);
        if (offsetStrategy && joffsetStrategy) env->ReleaseStringUTFChars(joffsetStrategy, offsetStrategy);
        rd_kafka_conf_destroy(conf);
        throwJavaException(env, errstr);
        return;
    }

    // Apply configuration to consumer
    rd_kafka_conf_set_opaque(conf, consumer);

    // Create topic partition list
    rd_kafka_topic_partition_list_t* topics = rd_kafka_topic_partition_list_new(1);
    rd_kafka_topic_partition_list_add(topics, topic, RD_KAFKA_PARTITION_UA);

    // Subscribe
    rd_kafka_resp_err_t err = rd_kafka_subscribe(consumer, topics);

    rd_kafka_topic_partition_list_destroy(topics);
    env->ReleaseStringUTFChars(jtopic, topic);
    if (offsetStrategy && joffsetStrategy) env->ReleaseStringUTFChars(joffsetStrategy, offsetStrategy);

    if (err != RD_KAFKA_RESP_ERR_NO_ERROR) {
        throwJavaException(env, rd_kafka_err2str(err));
    }
}

// Subscribe to topic with specific offset
JNIEXPORT void JNICALL
Java_org_github_cyterdan_chat_1over_1kafka_RdKafka_subscribeWithOffset(
        JNIEnv* env,
        jobject /* this */,
        jlong consumerPtr,
        jstring jtopic,
        jint partition,
        jlong offset) {

    if (consumerPtr == 0) {
        throwJavaException(env, "Consumer pointer is null");
        return;
    }
    if (!jtopic) {
        throwJavaException(env, "Topic cannot be null");
        return;
    }

    auto* consumer = reinterpret_cast<rd_kafka_t*>(consumerPtr);

    const char* topic = env->GetStringUTFChars(jtopic, nullptr);
    if (!topic) {
        throwJavaException(env, "Failed to get topic string from JNI");
        return;
    }

    // Create topic partition list with specific offset
    rd_kafka_topic_partition_list_t* topics = rd_kafka_topic_partition_list_new(1);
    rd_kafka_topic_partition_t* rktpar = rd_kafka_topic_partition_list_add(topics, topic, partition);
    rktpar->offset = offset;

    // Assign (not subscribe, since we're specifying exact partition and offset)
    rd_kafka_resp_err_t err = rd_kafka_assign(consumer, topics);

    rd_kafka_topic_partition_list_destroy(topics);
    env->ReleaseStringUTFChars(jtopic, topic);

    if (err != RD_KAFKA_RESP_ERR_NO_ERROR) {
        throwJavaException(env, rd_kafka_err2str(err));
    }
}

// Poll for a message
JNIEXPORT jobject JNICALL
Java_org_github_cyterdan_chat_1over_1kafka_RdKafka_pollMessage(
        JNIEnv* env,
        jobject /* this */,
        jlong consumerPtr,
        jint timeoutMs) {

    if (consumerPtr == 0) {
        throwJavaException(env, "Consumer pointer is null");
        return nullptr;
    }

    auto* consumer = reinterpret_cast<rd_kafka_t*>(consumerPtr);

    rd_kafka_message_t* rkmessage = rd_kafka_consumer_poll(consumer, timeoutMs);

    if (!rkmessage) {
        // Timeout, no message available
        return nullptr;
    }

    // Check for errors
    if (rkmessage->err) {
        if (rkmessage->err == RD_KAFKA_RESP_ERR__PARTITION_EOF) {
            // End of partition, not really an error
            rd_kafka_message_destroy(rkmessage);
            return nullptr;
        }

        const char* errstr = rd_kafka_message_errstr(rkmessage);
        rd_kafka_message_destroy(rkmessage);
        throwJavaException(env, errstr);
        return nullptr;
    }

    // Find KafkaMessage class
    jclass messageClass = env->FindClass("org/github/cyterdan/chat_over_kafka/KafkaMessage");
    if (!messageClass) {
        rd_kafka_message_destroy(rkmessage);
        throwJavaException(env, "Failed to find KafkaMessage class");
        return nullptr;
    }

    // Get constructor: KafkaMessage(byte[] key, byte[] value, String topic, int partition, long offset)
    jmethodID constructor = env->GetMethodID(messageClass, "<init>", "([B[BLjava/lang/String;IJ)V");
    if (!constructor) {
        rd_kafka_message_destroy(rkmessage);
        throwJavaException(env, "Failed to find KafkaMessage constructor");
        return nullptr;
    }

    // Create byte arrays for key and value
    jbyteArray jkey = nullptr;
    if (rkmessage->key && rkmessage->key_len > 0) {
        jkey = env->NewByteArray(rkmessage->key_len);
        env->SetByteArrayRegion(jkey, 0, rkmessage->key_len, static_cast<const jbyte*>(rkmessage->key));
    }

    jbyteArray jvalue = nullptr;
    if (rkmessage->payload && rkmessage->len > 0) {
        jvalue = env->NewByteArray(rkmessage->len);
        env->SetByteArrayRegion(jvalue, 0, rkmessage->len, static_cast<const jbyte*>(rkmessage->payload));
    }

    // Get topic name
    jstring jtopic = env->NewStringUTF(rd_kafka_topic_name(rkmessage->rkt));

    // Create KafkaMessage object
    jobject messageObj = env->NewObject(
            messageClass,
            constructor,
            jkey,
            jvalue,
            jtopic,
            static_cast<jint>(rkmessage->partition),
            static_cast<jlong>(rkmessage->offset)
    );

    rd_kafka_message_destroy(rkmessage);

    return messageObj;
}

// Close consumer
JNIEXPORT void JNICALL
Java_org_github_cyterdan_chat_1over_1kafka_RdKafka_closeConsumer(
        JNIEnv* env,
        jobject /* this */,
        jlong consumerPtr) {

    if (consumerPtr == 0) {
        return;
    }

    auto* consumer = reinterpret_cast<rd_kafka_t*>(consumerPtr);

    // Close consumer (commit offsets and leave group)
    rd_kafka_resp_err_t err = rd_kafka_consumer_close(consumer);
    if (err != RD_KAFKA_RESP_ERR_NO_ERROR) {
        // Log but don't throw, we're cleaning up
    }

    // Destroy consumer
    rd_kafka_destroy(consumer);
}


JNIEXPORT jobject JNICALL
Java_org_github_cyterdan_chat_1over_1kafka_RdKafka_produceMessage(
        JNIEnv* env,
        jobject,
        jlong producerPtr,
        jstring jtopic,
        jstring jkey,
        jstring jvalue) {

    if (!producerPtr || !jtopic || !jvalue) {
        throwJavaException(env, "Invalid arguments");
        return nullptr;
    }

    auto* producer = reinterpret_cast<rd_kafka_t*>(producerPtr);

    const char* topic = env->GetStringUTFChars(jtopic, nullptr);
    const char* value = env->GetStringUTFChars(jvalue, nullptr);
    const char* key = jkey ? env->GetStringUTFChars(jkey, nullptr) : nullptr;

    size_t value_len = env->GetStringUTFLength(jvalue);
    size_t key_len = jkey ? env->GetStringUTFLength(jkey) : 0;

    DeliveryState state;

    rd_kafka_resp_err_t err;
    if (key) {
        err = rd_kafka_producev(
                producer,
                RD_KAFKA_V_TOPIC(topic),
                RD_KAFKA_V_KEY((void*)key, key_len),
                RD_KAFKA_V_VALUE((void*)value, value_len),
                RD_KAFKA_V_MSGFLAGS(RD_KAFKA_MSG_F_COPY),
                RD_KAFKA_V_OPAQUE(&state),
                RD_KAFKA_V_END
        );
    } else {
        err = rd_kafka_producev(
                producer,
                RD_KAFKA_V_TOPIC(topic),
                RD_KAFKA_V_VALUE((void*)value, value_len),
                RD_KAFKA_V_MSGFLAGS(RD_KAFKA_MSG_F_COPY),
                RD_KAFKA_V_OPAQUE(&state),
                RD_KAFKA_V_END
        );
    }

    env->ReleaseStringUTFChars(jtopic, topic);
    env->ReleaseStringUTFChars(jvalue, value);
    if (key) env->ReleaseStringUTFChars(jkey, key);

    if (err != RD_KAFKA_RESP_ERR_NO_ERROR) {
        throwJavaException(env, rd_kafka_err2str(err));
        return nullptr;
    }

    // 🔒 BLOCK until delivery report
    {
        while (!state.done.load(std::memory_order_acquire)) {
            // Poll WITHOUT holding the mutex
            rd_kafka_poll(producer, 100);

            // Wait briefly (optional, avoids busy spin)
            std::unique_lock<std::mutex> lock(state.mtx);
            state.cv.wait_for(lock, std::chrono::milliseconds(50));
        }
    }

    if (state.err != RD_KAFKA_RESP_ERR_NO_ERROR) {
        throwJavaException(env, rd_kafka_err2str(state.err));
        return nullptr;
    }

    // Construct Kotlin RecordMetadata
    jclass cls = env->FindClass(
            "org/github/cyterdan/chat_over_kafka/RecordMetadata");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(IJ)V");

    return env->NewObject(
            cls,
            ctor,
            state.partition,
            (jlong)state.offset
    );
}



JNIEXPORT void JNICALL
Java_org_github_cyterdan_chat_1over_1kafka_RdKafka_flushProducer(
        JNIEnv* env,
        jobject /* this */,
        jlong producerPtr,
        jint timeoutMs) {

    if (producerPtr == 0) {
        throwJavaException(env, "Producer pointer is null");
        return;
    }

    auto* producer = reinterpret_cast<rd_kafka_t*>(producerPtr);
    rd_kafka_resp_err_t err = rd_kafka_flush(producer, timeoutMs);

    if (err != RD_KAFKA_RESP_ERR_NO_ERROR) {
        throwJavaException(env, rd_kafka_err2str(err));
    }
}

JNIEXPORT void JNICALL
Java_org_github_cyterdan_chat_1over_1kafka_RdKafka_destroyProducer(
        JNIEnv *env,
        jobject /* this */,
        jlong producerPtr) {

    if (producerPtr == 0) return;
    auto *producer = reinterpret_cast<rd_kafka_t *>(producerPtr);
    rd_kafka_destroy(producer);
}

} // extern "C"
