package com.keyquest.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.keyquest.app.ui.LessonPlayerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LessonPlayerScreen()
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 720, heightDp = 360)
@Composable
private fun LessonPlayerPreview() {
    MaterialTheme {
        LessonPlayerScreen()
    }
}