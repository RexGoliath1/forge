package forge;

import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.BuildInfo;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;

import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Headless implementation of IGuiBase for daemon/server mode.
 * Provides minimal implementations that don't require AWT/Swing.
 */
public class GuiHeadless implements IGuiBase {

    private static final ImageFetcher NOOP_IMAGE_FETCHER = new NoOpImageFetcher();

    @Override
    public boolean isRunningOnDesktop() {
        return true;
    }

    @Override
    public boolean isLibgdxPort() {
        return false;
    }

    @Override
    public String getCurrentVersion() {
        return BuildInfo.getVersionString();
    }

    @Override
    public String getAssetsDir() {
        // For development builds, assets are in ../forge-gui/
        return BuildInfo.isDevelopmentVersion() ? "../forge-gui/" : "";
    }

    @Override
    public ImageFetcher getImageFetcher() {
        return NOOP_IMAGE_FETCHER;
    }

    @Override
    public void invokeInEdtNow(Runnable runnable) {
        // No EDT in headless mode, just run directly
        runnable.run();
    }

    @Override
    public void invokeInEdtLater(Runnable runnable) {
        // No EDT in headless mode, just run directly
        runnable.run();
    }

    @Override
    public void invokeInEdtAndWait(Runnable proc) {
        // No EDT in headless mode, just run directly
        proc.run();
    }

    @Override
    public boolean isGuiThread() {
        return true; // No GUI thread distinction in headless mode
    }

    @Override
    public ISkinImage getSkinIcon(FSkinProp skinProp) {
        return null;
    }

    @Override
    public ISkinImage getUnskinnedIcon(String path) {
        return null;
    }

    @Override
    public ISkinImage getCardArt(PaperCard card) {
        return null;
    }

    @Override
    public ISkinImage getCardArt(PaperCard card, boolean backFace) {
        return null;
    }

    @Override
    public ISkinImage createLayeredImage(PaperCard card, FSkinProp background, String overlayFilename, float opacity) {
        return null;
    }

    @Override
    public void showBugReportDialog(String title, String text, boolean showExitAppBtn) {
        System.err.println("Bug Report: " + title + "\n" + text);
    }

    @Override
    public void showImageDialog(ISkinImage image, String message, String title) {
        // No-op in headless mode
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        return defaultOption;
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) {
        return initialInput;
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, Collection<T> choices, Collection<T> selected, FSerializableFunction<T, String> display) {
        return Collections.emptyList();
    }

    @Override
    public <T> List<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices) {
        return destChoices;
    }

    @Override
    public String showFileDialog(String title, String defaultDir) {
        return null;
    }

    @Override
    public File getSaveFile(File defaultFile) {
        return defaultFile;
    }

    @Override
    public void download(GuiDownloadService service, Consumer<Boolean> callback) {
        callback.accept(false);
    }

    @Override
    public void refreshSkin() {
        // No-op in headless mode
    }

    @Override
    public void showCardList(String title, String message, List<PaperCard> list) {
        // No-op in headless mode
    }

    @Override
    public boolean showBoxedProduct(String title, String message, List<PaperCard> list) {
        return false;
    }

    @Override
    public PaperCard chooseCard(String title, String message, List<PaperCard> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public int getAvatarCount() {
        return 0;
    }

    @Override
    public int getSleevesCount() {
        return 0;
    }

    @Override
    public void copyToClipboard(String text) {
        // No-op in headless mode
    }

    @Override
    public void browseToUrl(String url) {
        // No-op in headless mode
    }

    @Override
    public boolean isSupportedAudioFormat(File file) {
        return false;
    }

    @Override
    public IAudioClip createAudioClip(String filename) {
        return new NoOpAudioClip();
    }

    @Override
    public IAudioMusic createAudioMusic(String filename) {
        return new NoOpAudioMusic();
    }

    @Override
    public void startAltSoundSystem(String filename, boolean isSynchronized) {
        // No-op in headless mode
    }

    @Override
    public void clearImageCache() {
        // No-op in headless mode
    }

    @Override
    public void showSpellShop() {
        // No-op in headless mode
    }

    @Override
    public void showBazaar() {
        // No-op in headless mode
    }

    @Override
    public IGuiGame getNewGuiGame() {
        return null;
    }

    @Override
    public HostedMatch hostMatch() {
        return null;
    }

    @Override
    public void runBackgroundTask(String message, Runnable task) {
        task.run();
    }

    @Override
    public String encodeSymbols(String str, boolean formatReminderText) {
        return str;
    }

    @Override
    public void preventSystemSleep(boolean preventSleep) {
        // No-op in headless mode
    }

    @Override
    public float getScreenScale() {
        return 1.0f;
    }

    @Override
    public UpnpServiceConfiguration getUpnpPlatformService() {
        return null;
    }

    // No-op ImageFetcher for headless mode
    private static class NoOpImageFetcher extends ImageFetcher {
        @Override
        public void fetchImage(String imageKey, Callback callback) {
            // No-op - don't fetch images in headless mode
        }

        @Override
        protected Runnable getDownloadTask(String[] downloadUrls, String destPath, Runnable notifyObservers) {
            // Return a no-op task
            return () -> {};
        }
    }

    // No-op audio implementations
    private static class NoOpAudioClip implements IAudioClip {
        @Override
        public void play(float value) {}
        @Override
        public void loop() {}
        @Override
        public void stop() {}
        @Override
        public boolean isDone() { return true; }
        @Override
        public void dispose() {}
    }

    private static class NoOpAudioMusic implements IAudioMusic {
        @Override
        public void play(Runnable onComplete) {
            if (onComplete != null) onComplete.run();
        }
        @Override
        public void pause() {}
        @Override
        public void resume() {}
        @Override
        public void stop() {}
        @Override
        public void setVolume(float value) {}
        @Override
        public boolean isPlaying() { return false; }
        @Override
        public void dispose() {}
    }
}
