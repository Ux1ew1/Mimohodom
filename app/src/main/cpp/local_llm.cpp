#include <jni.h>
#include <llama.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

static std::atomic<bool> cancelled{false};
static std::mutex generation_mutex;
static std::once_flag backend;
struct Deadline {
    std::chrono::steady_clock::time_point start = std::chrono::steady_clock::now();
    bool expired() const { return cancelled.load() || std::chrono::steady_clock::now()-start>std::chrono::seconds(120); }
};
static bool abort_generation(void *data) { return static_cast<Deadline *>(data)->expired(); }
static bool load_progress(float, void *data) { return !abort_generation(data); }
static std::string utf8(JNIEnv *env, jbyteArray array) {
    auto size=env->GetArrayLength(array);std::string out(size,'\0');
    env->GetByteArrayRegion(array,0,size,reinterpret_cast<jbyte *>(out.data()));return out;
}
extern "C" JNIEXPORT void JNICALL Java_app_fastnotes_LocalLlm_cancelNative(JNIEnv *,jclass) {cancelled.store(true);}
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_fastnotes_LocalLlm_generateNative(
    JNIEnv *env,jclass engine,jstring path,jobjectArray roles,jobjectArray texts,jint maximum,jint context_size,jint threads) {
    std::lock_guard<std::mutex> guard(generation_mutex);cancelled.store(false);Deadline deadline;
    try {
        jmethodID report = env->GetStaticMethodID(engine, "reportStage", "(I)V");
        if (!report) return nullptr;
        env->CallStaticVoidMethod(engine, report, 1);
        std::call_once(backend,[]{llama_backend_init();});
        const char *file=env->GetStringUTFChars(path,nullptr);std::string filename(file);env->ReleaseStringUTFChars(path,file);
        auto model_params=llama_model_default_params();model_params.n_gpu_layers=0;
        model_params.progress_callback=load_progress;model_params.progress_callback_user_data=&deadline;
        std::unique_ptr<llama_model,decltype(&llama_model_free)> model(llama_model_load_from_file(filename.c_str(),model_params),llama_model_free);
        if(deadline.expired())throw std::runtime_error("Загрузка модели остановлена или превысила 2 минуты.");
        if(!model)throw std::runtime_error("GGUF не загружен. Модель несовместима, повреждена или не хватает памяти.");
        env->CallStaticVoidMethod(engine, report, 2);
        auto params=llama_context_default_params();params.n_ctx=context_size;params.n_batch=256;params.n_ubatch=128;
        params.n_threads=threads;params.n_threads_batch=threads;params.abort_callback=abort_generation;params.abort_callback_data=&deadline;
        std::unique_ptr<llama_context,decltype(&llama_free)> ctx(llama_init_from_model(model.get(),params),llama_free);
        if(!ctx)throw std::runtime_error("Недостаточно памяти для контекста локальной модели.");
        auto count=env->GetArrayLength(roles);std::vector<std::string> role_strings,text_strings;role_strings.reserve(count);text_strings.reserve(count);
        for(jsize i=0;i<count;i++) {
            auto role=static_cast<jstring>(env->GetObjectArrayElement(roles,i));const char *value=env->GetStringUTFChars(role,nullptr);
            role_strings.emplace_back(value);env->ReleaseStringUTFChars(role,value);env->DeleteLocalRef(role);
            auto bytes=static_cast<jbyteArray>(env->GetObjectArrayElement(texts,i));text_strings.push_back(utf8(env,bytes));env->DeleteLocalRef(bytes);
        }
        std::vector<llama_chat_message> messages;for(jsize i=0;i<count;i++)messages.push_back({role_strings[i].c_str(),text_strings[i].c_str()});
        const char *chat_template=llama_model_chat_template(model.get(),nullptr);
        if(!chat_template)chat_template="chatml";
        int size=llama_chat_apply_template(chat_template,messages.data(),messages.size(),true,nullptr,0);
        if(size<0)throw std::runtime_error("Шаблон диалога этой GGUF пока не поддерживается. Выберите другую instruct-модель.");
        std::vector<char> prompt(size+1);llama_chat_apply_template(chat_template,messages.data(),messages.size(),true,prompt.data(),prompt.size());
        const auto *vocab=llama_model_get_vocab(model.get());int needed=-llama_tokenize(vocab,prompt.data(),size,nullptr,0,true,true);
        if(needed<=0||needed+maximum+8>static_cast<int>(llama_n_ctx(ctx.get())))throw std::runtime_error("Диалог слишком длинный для локального контекста. Начните новый чат или уменьшите число заметок.");
        std::vector<llama_token> tokens(needed);llama_tokenize(vocab,prompt.data(),size,tokens.data(),needed,true,true);
        for(int offset=0;offset<needed;offset+=256) {
            if(deadline.expired())throw std::runtime_error("Локальная генерация остановлена или превысила 2 минуты.");
            int chunk=std::min(256,needed-offset);auto batch=llama_batch_get_one(tokens.data()+offset,chunk);
            if(llama_decode(ctx.get(),batch)!=0)throw std::runtime_error("Не удалось обработать локальный контекст. Попробуйте меньшую модель.");
        }
        std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()),llama_sampler_free);
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_top_k(40));llama_sampler_chain_add(sampler.get(),llama_sampler_init_top_p(.9f,1));
        llama_sampler_chain_add(sampler.get(),llama_sampler_init_temp(.7f));llama_sampler_chain_add(sampler.get(),llama_sampler_init_dist(42));
        env->CallStaticVoidMethod(engine, report, 3);
        std::string result;
        for(int i=0;i<maximum;i++) {
            if(cancelled.load())throw std::runtime_error("Локальная генерация остановлена.");
            if(deadline.expired())break;
            llama_token token=llama_sampler_sample(sampler.get(),ctx.get(),-1);if(llama_vocab_is_eog(vocab,token))break;
            std::vector<char> piece(128);int length=llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,true);
            if(length<0){piece.resize(-length);length=llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,true);}
            if(length>0)result.append(piece.data(),length);
            if(result.size()>131072)throw std::runtime_error("Слишком большой ответ локальной модели.");
            if(i+1<maximum){auto batch=llama_batch_get_one(&token,1);if(llama_decode(ctx.get(),batch)!=0&&!deadline.expired())throw std::runtime_error("Локальная модель не смогла продолжить ответ.");}
        }
        if(cancelled.load())throw std::runtime_error("Локальная генерация остановлена.");
        if(deadline.expired()) {
            if(result.empty())throw std::runtime_error("Модель не успела начать ответ за 2 минуты. Попробуйте модель меньшего размера.");
            result += "\n\nОтвет прерван по лимиту времени.";
        }
        auto out=env->NewByteArray(result.size());env->SetByteArrayRegion(out,0,result.size(),reinterpret_cast<const jbyte *>(result.data()));return out;
    } catch(const std::exception &error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what());return nullptr;
    } catch(...) {env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"Локальная модель завершилась с ошибкой.");return nullptr;}
}
