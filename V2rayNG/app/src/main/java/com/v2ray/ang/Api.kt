package com.v2ray.ang.api

import com.google.gson.GsonBuilder
import io.reactivex.Single
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import okhttp3.ResponseBody

interface Api {
    @GET("mustafa13760806/v2/ray/main/main")
    fun getConfigList(): Single<ResponseBody>

    companion object {
        const val BASE_URL = "https://raw.githubusercontent.com/"
        const val CONFIG_PATH = "mustafa13760806/v2/ray/main/main"

        operator fun invoke(): Api {
            val interceptor = HttpLoggingInterceptor()
            interceptor.setLevel(HttpLoggingInterceptor.Level.BODY)
            val client: OkHttpClient = OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build()

            val gson = GsonBuilder()
                .setLenient()
                .create()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .client(client)
                .build()
                .create(Api::class.java)
        }
    }
}
