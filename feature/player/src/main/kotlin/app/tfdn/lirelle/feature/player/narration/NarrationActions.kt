package app.tfdn.lirelle.feature.player.narration

import app.tfdn.lirelle.feature.player.PlayerSettingsActions
import app.tfdn.lirelle.feature.player.SleepControls
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import app.tfdn.lirelle.feature.player.controller.PlayerController

/**
 * What the narration's bar, panel and sheets ask for. Its sections are the session's chapters: the commands are
 * the audiobook player's, the sentence buttons and the speed aside. The sleep timer's ([SleepControls.startSleep],
 * [SleepControls.cancelSleep]) are the player's too, and so is shaking to extend the timer
 * ([PlayerSettingsActions.setShakeToExtend]), the setting the sleep timer's sheet changes.
 */
interface NarrationActions : SleepControls, PlayerSettingsActions {
    fun playPause()

    fun previousSentence()

    fun nextSentence()

    /** Back by the configured interval (15 s by default). */
    fun back()

    /** Forward by the configured interval (30 s by default). */
    fun forward()

    /** [fraction] of the way through the current section, from 0 to 1. */
    fun seekInSection(fraction: Float)

    /**
     * The start of the section [index], among the narrated ones (the rows of the sections sheet), from where
     * "Go back" returns.
     */
    fun goToSection(index: Int)

    /** "Go back": where the narration was when the last section was chosen ([NarrationUi.goBackShown]). */
    fun goBack()

    fun setSpeed(speed: Float)

    /** "Stop listening": the narration is closed, its place saved first, and the service ends. */
    fun stop()
}

/**
 * The narration's controls carried out by this controller: a section chosen goes to its start as a chapter of the
 * audiobook would, and "Go back" returns from it; the controller keeps that place, for the bar and the panel alike.
 */
fun PlayerController.narrationActions(): NarrationActions = ControllerNarrationActions(this)

private class ControllerNarrationActions(private val controller: PlayerController) :
    NarrationActions,
    SleepControls by controller,
    PlayerSettingsActions by controller {

    override fun playPause() = controller.playPause()

    override fun previousSentence() = controller.previousSentence()

    override fun nextSentence() = controller.nextSentence()

    override fun back() = controller.back()

    override fun forward() = controller.forward()

    override fun seekInSection(fraction: Float) = controller.seekInChapter(fraction)

    override fun goToSection(index: Int) = controller.goToSection(index)

    override fun goBack() = controller.goBackInNarration()

    override fun setSpeed(speed: Float) = controller.narrationSpeed(speed)

    override fun stop() = controller.stopListening()
}
