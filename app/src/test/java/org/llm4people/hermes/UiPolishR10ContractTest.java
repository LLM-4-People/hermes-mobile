package org.llm4people.hermes;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM regression contract tests for Triple-Check Round 10 PR 3:
 * - R10.1.1 / R10.5.6: InstrumentChanges localized labels & FR/EN string keys (no raw wire tokens / Unicode arrow).
 * - R10.4.4: Zero redundant org.llm4people.hermes.R FQNs in InteractiveControlsBlock, DataTableBlock, LiveUiPatchBar.
 * - R10.4.5: All 21 confirmed zero-caller production declarations stay removed.
 * - R10.7.1: All 7 Javadocs accurately reflect RouteRegistry.Section mounts (Apps / Notes & Tasks / Settings).
 */
public final class UiPolishR10ContractTest {

    private static String read(String relPath) throws IOException {
        Path direct = Paths.get(relPath);
        Path target = Files.exists(direct) ? direct : Paths.get("app", relPath);
        return new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
    }

    @Test
    public void instrumentChangesAndStringsLocalizeAllDiffValues() throws IOException {
        String changes = read("src/main/java/org/llm4people/hermes/artifact/InstrumentChanges.java");
        assertFalse("InstrumentChanges must not hardcode raw 'source=' prefix", changes.contains("\"source=\""));
        assertFalse("InstrumentChanges must not hardcode raw Unicode arrow", changes.contains("\" → \""));
        assertTrue(changes.contains("R.string.ws_diff_section_renamed"));
        assertTrue(changes.contains("NearbyCategory.fromId("));
        assertTrue(changes.contains("kindLabelRes()"));
        assertTrue(changes.contains("WorkspaceBlocks.sourceLabel("));
        assertTrue(changes.contains("R.string.ws_diff_ctrl_toggle"));
        assertTrue(changes.contains("R.string.ws_diff_ctrl_checkbox"));
        assertTrue(changes.contains("R.string.ws_diff_ctrl_slider"));
        assertTrue(changes.contains("R.string.ws_diff_ctrl_chips"));

        String fr = read("src/main/res/values/i18n_workspace_strings.xml");
        String en = read("src/main/res/values-en/i18n_workspace_strings.xml");
        for (String key : new String[]{
                "ws_diff_section_renamed",
                "ws_diff_ctrl_toggle",
                "ws_diff_ctrl_checkbox",
                "ws_diff_ctrl_slider",
                "ws_diff_ctrl_chips"
        }) {
            assertTrue("FR missing " + key, fr.contains("name=\"" + key + "\""));
            assertTrue("EN missing " + key, en.contains("name=\"" + key + "\""));
        }
    }

    @Test
    public void noMaskedRImportFqnsInArtifactViews() throws IOException {
        for (String rel : new String[]{
                "src/main/java/org/llm4people/hermes/artifact/InteractiveControlsBlock.java",
                "src/main/java/org/llm4people/hermes/artifact/DataTableBlock.java",
                "src/main/java/org/llm4people/hermes/artifact/LiveUiPatchBar.java"
        }) {
            String src = read(rel);
            assertTrue(rel + " must import org.llm4people.hermes.R",
                    src.contains("import org.llm4people.hermes.R;"));
            assertFalse(rel + " must not use redundant org.llm4people.hermes.R. FQN",
                    src.contains("org.llm4people.hermes.R."));
        }
    }

    @Test
    public void deadDeclarationsRemainRemoved() throws IOException {
        assertFalse(read("src/main/java/org/llm4people/hermes/panel/SearchResultModel.java").contains("MAX_LIMIT"));
        assertFalse(read("src/main/java/org/llm4people/hermes/os/HermesWidgetProvider.java").contains("String statusText("));
        assertFalse(read("src/main/java/org/llm4people/hermes/thread/ThreadData.java").contains("int pageSize("));
        assertFalse(read("src/main/java/org/llm4people/hermes/customization/AppCustomizationStore.java").contains("void wipe("));
        assertFalse(read("src/main/java/org/llm4people/hermes/customization/AppCustomizationStore.java").contains("#wipe("));
        assertFalse(read("src/main/java/org/llm4people/hermes/presence/PresencePolicy.java").contains("void wipe("));
        assertFalse(read("src/main/java/org/llm4people/hermes/search/UniversalSearchModel.java").contains("LocalEntry note("));
        assertFalse(read("src/main/java/org/llm4people/hermes/thread/LiveTranscript.java").contains("coalescer("));
        assertFalse(read("src/main/java/org/llm4people/hermes/camera/ThreadCameraCapture.java").contains("Uri pendingSlot("));
        assertFalse(read("src/main/java/org/llm4people/hermes/artifact/DataTableBlock.java").contains("String filterQuery("));
        assertFalse(read("src/main/java/org/llm4people/hermes/push/PushPolicy.java").contains("enabledCategories()"));
        assertFalse(read("src/main/java/org/llm4people/hermes/thread/InfiniteTranscriptWindow.java").contains("int maxWindowRows()"));
        assertFalse(read("src/main/java/org/llm4people/hermes/thread/InfiniteTranscriptWindow.java").contains("int legacyOffset()"));
        assertFalse(read("src/main/java/org/llm4people/hermes/thread/TranscriptCellCache.java").contains("int capacity()"));
        assertFalse(read("src/main/java/org/llm4people/hermes/presence/AppPresenceTracker.java").contains("long currentEpoch()"));
        assertFalse(read("src/main/java/org/llm4people/hermes/presence/AppPresenceTracker.java").contains("String boundProfileId()"));
        assertFalse(read("src/main/java/org/llm4people/hermes/presence/PresenceSettingsPanel.java").contains("PresencePolicy currentPolicy()"));
        assertFalse(read("src/main/java/org/llm4people/hermes/perf/StreamCoalescer.java").contains("StreamCoalescer(Consumer<String>"));
        assertFalse(read("src/main/java/org/llm4people/hermes/perf/StreamCoalescer.java").contains("long frameBudgetMs()"));
        assertFalse(read("src/main/java/org/llm4people/hermes/thread/ActiveRunTracker.java").contains("boolean isActive("));
        assertFalse(read("src/main/java/org/llm4people/hermes/thread/ThreadTailCache.java").contains("String activeProfileId()"));
        assertFalse(read("src/main/java/org/llm4people/hermes/onboarding/QrCodeView.java").contains("QrCodeMatrix matrix()"));
    }

    @Test
    public void sectionJavadocsMatchRouteRegistry() throws IOException {
        assertTrue(read("src/main/java/org/llm4people/hermes/templates/TemplatesPanel.java").contains("under Apps"));
        assertTrue(read("src/main/java/org/llm4people/hermes/update/UpdatePanel.java").contains("under Settings"));
        assertTrue(read("src/main/java/org/llm4people/hermes/update/UpdatePanel.java").contains("non-intrusive Settings badge"));
        assertTrue(read("src/main/java/org/llm4people/hermes/update/UpdateCheckCoordinator.java").contains("badge state for Settings"));
        assertTrue(read("src/main/java/org/llm4people/hermes/doctor/ConnectionDoctorPanel.java").contains("under Settings"));
        assertTrue(read("src/main/java/org/llm4people/hermes/push/DeliveryRouter.java")
                .contains("Native delivery inbox (Notes &amp; Tasks) and push policy (Settings)"));
        assertTrue(read("src/main/java/org/llm4people/hermes/panel/NotesPanel.java").contains("under Notes &amp; Tasks"));
        String share = read("src/main/java/org/llm4people/hermes/panel/ShareWithUserSheet.java");
        assertTrue(share.contains("Custom Screens/Tools (Apps)"));
        assertTrue(share.contains("under Notes &amp; Tasks"));
    }

    @Test
    public void round11SsotAndPolishContractsHold() throws IOException {
        // R11.4.2: Public Ui.enabledDisabledInk(int, int) SSoT across all artifact/card views.
        String ui = read("src/main/java/org/llm4people/hermes/Ui.java");
        assertTrue(ui.contains("public static ColorStateList enabledDisabledInk(int enabledColor, int disabledColor)"));
        for (String rel : new String[]{
                "src/main/java/org/llm4people/hermes/artifact/InteractiveControlsBlock.java",
                "src/main/java/org/llm4people/hermes/artifact/DataTableBlock.java",
                "src/main/java/org/llm4people/hermes/customization/InteractiveCardView.java"
        }) {
            String src = read(rel);
            assertFalse(rel + " must delegate to Ui.enabledDisabledInk instead of new ColorStateList",
                    src.contains("new ColorStateList("));
            assertTrue(rel + " must use Ui.enabledDisabledInk(", src.contains("Ui.enabledDisabledInk("));
        }

        // R11.4.4 & R11.7.1: Vault key SSoT & LiveUiPatch Apps Javadoc.
        String liveUi = read("src/main/java/org/llm4people/hermes/artifact/LiveUiPatch.java");
        assertFalse(liveUi.contains("\"workspace.theme.v2\""));
        assertTrue(liveUi.contains("ThemePalette.STORAGE_KEY"));
        assertTrue(liveUi.contains("custom Apps tabs"));
        assertFalse(liveUi.contains("custom Atelier tabs"));
        String desk = read("src/main/java/org/llm4people/hermes/artifact/ArtifactDesk.java");
        assertFalse(desk.contains("\"workspace.live_ui.v2\""));
        assertTrue(desk.contains("LiveUiPatch.STATE_KEY"));

        // R11.1.1 / R11.5.1 / R11.5.2 / R11.5.3 contracts.
        String parts = read("src/main/java/org/llm4people/hermes/artifact/MessageParts.java");
        assertTrue(parts.contains("AppLanguage.wrapContext(context).getString(R.string.ws_image_attached)"));
        String promptButtons = read("src/main/java/org/llm4people/hermes/thread/PromptButtons.java");
        assertTrue(promptButtons.contains("colors(context, R.color.disabled_ink, text, text)"));
        String versions = read("src/main/java/org/llm4people/hermes/thread/InstrumentVersions.java");
        assertTrue(versions.contains("Ui.roundedRipple(c, 6, surface)"));
        assertFalse(versions.contains("new RippleDrawable("));
        String studio = read("src/main/java/org/llm4people/hermes/panel/SkillStudio.java");
        assertTrue(studio.contains("toggle.setMinWidth(minTouch)"));
        assertTrue(studio.contains("toggle.setMinimumWidth(minTouch)"));

        // R12.5.1 / R12.4.4 / R12.4.5 contracts.
        String patchBar = read("src/main/java/org/llm4people/hermes/artifact/LiveUiPatchBar.java");
        assertTrue(patchBar.contains("heading.setTextColor(context.getColor(R.color.muted));"));
        assertFalse(patchBar.contains("heading.setTextColor(colors.muted);"));
        String surfaceRenderer = read("src/main/java/org/llm4people/hermes/artifact/SurfaceRenderer.java");
        assertFalse(surfaceRenderer.contains("ArtifactDesk.Host host, boolean showTitle)"));
        String nearbyGuide = read("src/main/java/org/llm4people/hermes/places/NearbyGuide.java");
        assertFalse(nearbyGuide.contains("public static String distance(int meters)"));

        // R31.4.2 / R31.5.3 / R31.5.4 contracts.
        String deviceSecret = read("src/main/java/org/llm4people/hermes/push/DeviceSecret.java");
        assertTrue(deviceSecret.contains("Digests.toHex(bytes)"));
        assertFalse(deviceSecret.contains("Character.forDigit"));

        String reminderPanel = read("src/main/java/org/llm4people/hermes/panel/ReminderPanel.java");
        assertTrue(reminderPanel.contains("Ui.announceLiveStatus(feedbackView, panel.t(R.string.panel_jobs_triggered))"));
        assertTrue(reminderPanel.contains("Ui.announceLiveStatus(feedbackView, panel.t(R.string.panel_jobs_trigger_failed))"));

        String skillBrowser = read("src/main/java/org/llm4people/hermes/panel/SkillBrowser.java");
        assertTrue(skillBrowser.contains("Ui.announceLiveStatus(empty, msg)"));
        assertTrue(skillBrowser.contains("Ui.clearLiveStatus(empty)"));

        String promptCard = read("src/main/java/org/llm4people/hermes/thread/PromptCard.java");
        assertTrue(promptCard.contains("Ui.clearLiveStatus(status)"));
        assertTrue(promptCard.contains("Ui.announceLiveStatus(status, message)"));

        String profileGate = read("src/main/java/org/llm4people/hermes/profile/ProfileGate.java");
        assertTrue(profileGate.contains("TextView stagedSummary = screen.note(\"\");"));
        assertFalse(profileGate.contains("TextView stagedSummary = screen.liveNote(\"\");"));

        // R35.6.1-6: Single-dispatch dialog guards across all 6 confirmation/picker dialogs.
        for (String rel : new String[]{
                "src/main/java/org/llm4people/hermes/profile/ProfileInviteDialogs.java",
                "src/main/java/org/llm4people/hermes/profile/AdminConsoleSheet.java",
                "src/main/java/org/llm4people/hermes/templates/TemplatesPanel.java",
                "src/main/java/org/llm4people/hermes/settings/AppearanceSettingsPanel.java",
                "src/main/java/org/llm4people/hermes/WorkspaceDialogs.java"
        }) {
            String src = read(rel);
            assertTrue(rel + " must guard positive button with if (submitted[0]) return;",
                    src.contains("if (submitted[0]) return;"));
        }
        String appLang = read("src/main/java/org/llm4people/hermes/AppLanguage.java");
        assertTrue("AppLanguage.showPicker must guard single-choice click with if (handled[0]) return;",
                appLang.contains("if (handled[0]) return;"));

        // R36 PR B contracts: ShareDraftTray live region + line_strong, QrScannerActivity contrast + imageDecodePending,
        // single-dispatch dialog guards & polite live regions across panels/sheets.
        String shareTray = read("src/main/java/org/llm4people/hermes/share/ShareDraftTray.java");
        assertFalse("ShareDraftTray must not use deprecated announceForAccessibility",
                shareTray.contains("announceForAccessibility"));
        assertTrue(shareTray.contains("Ui.announceLiveStatus(liveStatus, notice)"));
        assertTrue(shareTray.contains("R.color.line_strong"));

        String qrScanner = read("src/main/java/org/llm4people/hermes/onboarding/QrScannerActivity.java");
        assertTrue(qrScanner.contains("0xCC182022"));
        assertTrue(qrScanner.contains("0xB3FFFFFF"));
        assertTrue(qrScanner.contains("imageDecodePending"));

        String pairingShare = read("src/main/java/org/llm4people/hermes/onboarding/PairingShareSheet.java");
        assertTrue(pairingShare.contains("Ui.announceLiveStatus(countdownView, activity.getString(R.string.profile_hub_qr_expired))"));

        String wsFiles = read("src/main/java/org/llm4people/hermes/panel/WorkspaceFilesPanel.java");
        assertTrue(wsFiles.contains("BrainUi.liveNote(panel, body, panel.t(R.string.brain_files_empty))"));
        assertTrue(wsFiles.contains("BrainUi.liveNote(panel, body, panel.t(R.string.brain_files_no_changes))"));

        String shareSheet = read("src/main/java/org/llm4people/hermes/panel/ShareWithUserSheet.java");
        assertTrue(shareSheet.contains("BrainUi.liveNote(panel, body, panel.t(R.string.brain_share_none_outgoing))"));
        assertTrue(shareSheet.contains("BrainUi.liveNote(panel, body, panel.t(R.string.brain_shared_with_me_empty))"));

        String voiceDrafts = read("src/main/java/org/llm4people/hermes/voice/VoiceDrafts.java");
        assertTrue(voiceDrafts.contains("if (handled[0]) return;"));

        String notifListener = read("src/main/java/org/llm4people/hermes/ambient/HermesNotificationListener.java");
        assertTrue(notifListener.contains("if (!settled.compareAndSet(false, true)) return;"));

        String mediaBridge = read("src/main/java/org/llm4people/hermes/ambient/MediaContextBridge.java");
        assertTrue(mediaBridge.contains("if (!settled.compareAndSet(false, true)) return;"));

        String bridgeFlow = read("src/main/java/org/llm4people/hermes/share/DeviceBridgeFlow.java");
        assertTrue(bridgeFlow.contains("if (!actionDispatched.compareAndSet(false, true)) return;"));

        String firstRun = read("src/main/java/org/llm4people/hermes/profile/FirstRunWizard.java");
        assertTrue(firstRun.contains("if (submitted[0]) return;"));

        String nearbyConsent = read("src/main/java/org/llm4people/hermes/thread/NearbyConsent.java");
        assertTrue(nearbyConsent.contains("if (handled[0] || ticket != generation) return;"));

        // R37 PR B contracts: ShareDraftTray 1x1 transparent liveStatus, shared horizontal track styling,
        // live regions + dead param cleanup, localized settings load error, QrScanner generation guard,
        // destructive delete & picker single-dispatch guards, PairingShareSheet revoke-failure toast.
        assertTrue(shareTray.contains("new LinearLayout.LayoutParams(1, 1)"));
        assertTrue(shareTray.contains("Color.TRANSPARENT"));

        String uiSrc = read("src/main/java/org/llm4people/hermes/Ui.java");
        assertTrue(uiSrc.contains("public static void styleHorizontalProgressBar("));
        assertTrue(uiSrc.contains("public static LayerDrawable horizontalTrackDrawable("));
        assertTrue(read("src/main/java/org/llm4people/hermes/update/UpdatePanel.java")
                .contains("Ui.styleHorizontalProgressBar("));
        assertTrue(read("src/main/java/org/llm4people/hermes/doctor/ConnectionDoctorPanel.java")
                .contains("Ui.styleHorizontalProgressBar("));
        String controlsBlock = read("src/main/java/org/llm4people/hermes/artifact/InteractiveControlsBlock.java");
        assertTrue(controlsBlock.contains("Ui.horizontalTrackDrawable("));
        assertTrue(controlsBlock.contains("seek.setThumbTintList("));

        String deliveryRouter = read("src/main/java/org/llm4people/hermes/push/DeliveryRouter.java");
        assertTrue(deliveryRouter.contains("note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE)"));
        assertTrue(deliveryRouter.contains("if (handled[0]) return;"));

        assertTrue(firstRun.contains("ready.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE)"));
        assertFalse("FirstRunWizard.showManualPasteDialog must not keep dead statusNote param",
                firstRun.contains("showManualPasteDialog(TextView statusNote)"));

        String adminConsole = read("src/main/java/org/llm4people/hermes/profile/AdminConsoleSheet.java");
        assertTrue(adminConsole.contains("R.string.admin_console_users_load_failed"));
        assertTrue(adminConsole.contains("R.string.admin_console_settings_load_failed"));

        assertTrue(qrScanner.contains("imageDecodeGeneration"));
        assertTrue(qrScanner.contains("if (gen != imageDecodeGeneration) return;"));
        assertTrue(qrScanner.contains("int effectiveDetailRes = imageDecodePending ? R.string.qr_scanner_reading_image : detailRes;"));

        for (String rel : new String[]{
                "src/main/java/org/llm4people/hermes/panel/MemoryPanel.java",
                "src/main/java/org/llm4people/hermes/panel/NotesPanel.java",
                "src/main/java/org/llm4people/hermes/panel/ReminderPanel.java",
                "src/main/java/org/llm4people/hermes/panel/SkillStudio.java"
        }) {
            String src = read(rel);
            assertTrue(rel + " must guard destructive delete dialog with if (handled[0]) return;",
                    src.contains("if (handled[0]) return;"));
        }
        assertTrue(read("src/main/java/org/llm4people/hermes/panel/ReminderPanel.java")
                .contains("v.setEnabled(false);"));
        assertTrue(voiceDrafts.contains("if (handled[0] || recovery != this || paused || !target.visible()) return;"));
        assertTrue(pairingShare.contains("R.string.admin_settings_revoke_failed"));
    }
}
