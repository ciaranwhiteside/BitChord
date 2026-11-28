package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MicExternalOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.karaoke_failed
import com.music.bitchord.sharedui.resources.karaoke_mix_paused
import com.music.bitchord.sharedui.resources.karaoke_not_on_output
import com.music.bitchord.sharedui.resources.karaoke_preparing
import com.music.bitchord.sharedui.resources.karaoke_start
import com.music.bitchord.sharedui.resources.karaoke_stop
import com.music.bitchord.sharedui.resources.karaoke_vocal_level
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.canSing
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Karaoke in the lyrics view, after Apple Music's Sing: a microphone disc beside the
 * translate button turns it on, and while it is on a slider sets how much of the original
 * vocal stays. Offered only for lyrics timed word by word ([canSing]); the reasons it can't
 * cut (this output, a song it couldn't read) and the paused mix are said under it.
 */
@Composable
internal fun KaraokeControl(lines: List<LyricLine>?, enabled: Boolean = true) {
    if (lines.isNullOrEmpty() || !lines.canSing) return
    val settings = PlayerSettings
    val on by settings.karaokeEnabled.collectAsStateWithLifecycle()
    val status by settings.karaokeStatus.collectAsStateWithLifecycle()
    val saved by settings.karaokeVocalLevel.collectAsStateWithLifecycle()
    val mixing by settings.smartFadeEnabled.collectAsStateWithLifecycle()
    var level by remember { mutableFloatStateOf(saved) }
    LaunchedEffect(saved) { level = saved }
    val chrome = LocalPlayerChrome.current

    val note = when {
        !on -> null
        status == KaraokeStatus.NotOnThisOutput -> stringResource(Res.string.karaoke_not_on_output)
        status == KaraokeStatus.Failed -> stringResource(Res.string.karaoke_failed)
        mixing -> stringResource(Res.string.karaoke_mix_paused)
        else -> null
    }
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(
                visible = on,
                enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End),
            ) {
                val label = stringResource(Res.string.karaoke_vocal_level)
                val set = { value: Float ->
                    level = value.coerceIn(0f, 1f)
                    settings.setKaraokeVocalLevel(level)
                }
                ThinSlider(
                    value = level,
                    onValueChange = set,
                    idleHeight = 6.dp,
                    activeHeight = 10.dp,
                    modifier = Modifier
                        .width(112.dp)
                        .padding(end = 10.dp)
                        .semantics {
                            contentDescription = label
                            stateDescription = "${(level * 100).roundToInt()}%"
                            progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f)
                            setProgress { set(it); true }
                        },
                )
            }
            val discAlpha by animateFloatAsState(if (on) 0.34f else 0.18f, label = "karaokeDisc")
            val tint = when {
                !enabled -> chromeWhite(0.42f)
                on && status != KaraokeStatus.Singing && status != KaraokeStatus.Preparing -> chromeWhite(0.42f)
                on -> Color.White.copy(alpha = if (chrome) CHROME_FULL_ALPHA else 1f)
                else -> chromeWhite(0.78f)
            }
            val description = when {
                !on -> stringResource(Res.string.karaoke_start)
                status == KaraokeStatus.Preparing -> stringResource(Res.string.karaoke_preparing)
                else -> stringResource(Res.string.karaoke_stop)
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .additiveChrome(chrome)
                    .background(Color.White.copy(alpha = discAlpha))
                    .clickable(
                        enabled = enabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { settings.setKaraokeEnabled(!on) },
                    )
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                if (on && status == KaraokeStatus.Preparing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = tint, strokeWidth = 1.7.dp)
                } else {
                    Icon(Icons.Rounded.MicExternalOn, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
                }
            }
        }
        if (note != null) {
            Text(note, color = chromeWhite(0.62f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
