/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei.legacy

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import com.google.android.apps.muzei.api.MuzeiArtSource
import com.google.android.apps.muzei.api.UserCommand
import com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_HANDLE_COMMAND
import com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_COMMAND_ID
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.R
import okhttp3.Request
import java.io.IOException
import java.net.URISyntaxException
import java.net.URL

/**
 * A MuzeiArtProvider that encapsulates all of the logic for working with MuzeiArtSources
 */
class SourceArtProvider : MuzeiArtProvider() {

    companion object {
        private const val TAG = "SourceArtProvider"
    }

    override fun onLoadRequested(initial: Boolean) {
        if (initial) {
            // If there's no artwork at all, immediately queue up the next artwork
            sendAction(MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK)
        }
        // else, Sources will load on their own schedule
    }

    override fun getDescription(): String = context?.let { context ->
        LegacyDatabase.getInstance(context).sourceDao().currentSourceBlocking?.run {
            listOf(label, displayDescription)
                    .asSequence()
                    .filterNot { it.isNullOrEmpty() }
                    .joinToString(separator = ": ")
        }
    } ?: ""

    @SuppressLint("Range")
    override fun getCommands(artwork: Artwork): List<UserCommand> = context?.let { context ->
        LegacyDatabase.getInstance(context).sourceDao().currentSourceBlocking?.run {
            mutableListOf<UserCommand>().apply {
                if (supportsNextArtwork) {
                    add(UserCommand(MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK,
                            context.getString(R.string.legacy_action_next_artwork)))
                }
                addAll(commands)
            }
        }
    } ?: super.getCommands(artwork)

    override fun onCommand(artwork: Artwork, id: Int) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Sending command $id for ${artwork.id}")
        }
        sendAction(id)
    }

    private fun sendAction(id: Int) = GlobalScope.launch(Dispatchers.Main) {
        val context = context ?: return@launch
        LegacyDatabase.getInstance(context).sourceDao().getCurrentSource()?.run {
            try {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Sending command $id to ${this}")
                }
                // Ensure that we have a valid service before sending the action
                context.packageManager.getServiceInfo(componentName, 0)
                context.startService(Intent(ACTION_HANDLE_COMMAND)
                        .setComponent(componentName)
                        .putExtra(EXTRA_COMMAND_ID, id))
            } catch (e: PackageManager.NameNotFoundException) {
                Log.i(TAG, "Sending action $id to $componentName failed as it is no longer available", e)
                context.toast(R.string.legacy_source_unavailable, Toast.LENGTH_LONG)
                LegacyDatabase.getInstance(context).sourceDao().delete(this)
            } catch (e: IllegalStateException) {
                Log.i(TAG, "Sending action $id to $componentName failed; unselecting it.", e)
                context.toast(R.string.legacy_source_unavailable, Toast.LENGTH_LONG)
                LegacyDatabase.getInstance(context).sourceDao()
                        .update(apply { selected = false })
            } catch (e: SecurityException) {
                Log.i(TAG, "Sending action $id to $componentName failed; unselecting it.", e)
                context.toast(R.string.legacy_source_unavailable, Toast.LENGTH_LONG)
                LegacyDatabase.getInstance(context).sourceDao()
                        .update(apply { selected = false })
            }
        }
    }

    override fun getArtworkInfo(artwork: Artwork): PendingIntent? {
        val context = context ?: return null
        val webUri = artwork.webUri ?: return null
        try {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Opening artwork info for ${artwork.id}")
            }
            // Try to parse the metadata as an Intent
            Intent.parseUri(webUri.toString(), Intent.URI_INTENT_SCHEME)?.run {
                // Make sure any data URIs granted to Muzei are passed onto the started Activity
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                return PendingIntent.getActivity(context, 0, this, 0)
            }
        } catch (e: URISyntaxException) {
            Log.i(TAG, "Unable to parse viewIntent ${this}", e)
        }
        return null
    }

    override fun openFile(artwork: Artwork) =
            artwork.persistentUri?.takeIf {
                it.scheme == "http" || it.scheme == "https"
            }?.run {
                val client = OkHttpClientFactory.getNewOkHttpsSafeClient()
                val request = Request.Builder().url(URL(toString())).build()
                val response = client.newCall(request).execute()
                val responseCode = response.code()
                if (responseCode !in 200..299) {
                    throw IOException("HTTP error response $responseCode")
                }
                val body = response.body()
                return body?.byteStream()
                        ?: throw IOException("Unable to open stream for $this")
            } ?: super.openFile(artwork)
}