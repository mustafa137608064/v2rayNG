package com.v2ray.ang.repository

import com.google.gson.GsonBuilder
import io.reactivex.Single
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*


interface Api {

    @GET("mustafa137608064/subdr/refs/heads/main/users/mustafa.php")
    fun getConfigsList(): Single<ResponseBody>

    companion object {
        operator fun invoke() : Api {
            val interceptor = HttpLoggingInterceptor()
            interceptor.setLevel(HttpLoggingInterceptor.Level.BODY)
            val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()

            val gson = GsonBuilder()
                .setLenient()
                .create()

            return Retrofit.Builder()
                .baseUrl("https://raw.githubusercontent.com/")
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .client(client)
                .build()
                .create(Api::class.java)
        }
    }
}
