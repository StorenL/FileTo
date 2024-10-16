package com.storen.fileto.transfer.ext

import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

object Transfer {
    fun setup() {
        // TODO
    }

    fun clear() {
        // TODO
    }
}

fun Any.transfer() {
    when(this) {
        is File -> {

        }
        is Bitmap -> {

        }
        is View -> {

        }
        is String -> {
            this.toByteArray()
        }
        is Serializable -> {
            // TODO ObjectOutputStream or JSON?
        }
    }
}