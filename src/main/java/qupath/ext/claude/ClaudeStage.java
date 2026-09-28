package qupath.ext.claude;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.scripting.ScriptEditor;

/** Simple chat-style window that shells out to the {@code claude} CLI. */
public class ClaudeStage extends Stage {

    private static final Pattern GROOVY_BLOCK =
            Pattern.compile("```(?:groovy|java)?\\s*\\n(.*?)```", Pattern.DOTALL);

    private final QuPathGUI qupath;
    private final TextArea output = new TextArea();
    private final TextField input = new TextField();
    private final Button send = new Button("Send");
    private final Button openScript = new Button("Open last script in editor");
    private final CheckBox includeContext = new CheckBox("Include image/selection context");
    private final TextField claudePath = new TextField();
    private final javafx.scene.control.ComboBox<String> attach =
            new javafx.scene.control.ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                    ATTACH_NONE, ATTACH_SELECTION, ATTACH_VIEWPORT));
    private final Button skillFolder = new Button("Pathology skill folder");
    private final Button exportMd = new Button("Export as Markdown");
    private final javafx.scene.control.ComboBox<String> modelChoice =
            new javafx.scene.control.ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                    MODEL_DEFAULT, "sonnet", "opus", "fable", "haiku"));
    private final javafx.scene.control.Label modelLabel = new javafx.scene.control.Label();
    private volatile String lastScript;
    private volatile String lastPrompt;
    private volatile String lastResponse;
    private static final String ATTACH_NONE = "No image";
    private static final String ATTACH_SELECTION = "Attach selected region";
    private static final String ATTACH_VIEWPORT = "Attach current viewport";
    private static final int MAX_IMAGE_SIDE = 1568;
    private static final String MODEL_DEFAULT = "(CLI default)";
    private static final java.time.format.DateTimeFormatter FILE_STAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private static final javafx.beans.property.StringProperty CLAUDE_CMD =
            PathPrefs.createPersistentPreference("ext.claude.command", "claude");
    private static final javafx.beans.property.StringProperty MODEL_PREF =
            PathPrefs.createPersistentPreference("ext.claude.model", MODEL_DEFAULT);
    private static final javafx.beans.property.StringProperty EXPORT_DIR_PREF =
            PathPrefs.createPersistentPreference("ext.claude.exportDir",
                    java.nio.file.Path.of(System.getProperty("user.home"), "QuPath", "claude-notes").toString());

    public ClaudeStage(QuPathGUI qupath) {
        this.qupath = qupath;
        setTitle("Claude Code");
        initOwner(qupath.getStage());

        output.setEditable(false);
        output.setWrapText(true);
        input.setPromptText("Ask Claude, e.g. 'write a script to count tumour cells per annotation'");
        HBox.setHgrow(input, Priority.ALWAYS);
        input.setOnAction(e -> send());
        send.setOnAction(e -> send());
        openScript.setDisable(true);
        openScript.setOnAction(e -> openInEditor());
        includeContext.setSelected(true);
        claudePath.textProperty().bindBidirectional(CLAUDE_CMD);
        claudePath.setPromptText("claude executable");

        attach.getSelectionModel().selectFirst();
        skillFolder.setOnAction(e -> openSkillFolder());
        exportMd.setDisable(true);
        exportMd.setTooltip(new javafx.scene.control.Tooltip("Saves the last response to " + EXPORT_DIR_PREF.get()));
        exportMd.setOnAction(e -> exportMarkdown());

        String savedModel = MODEL_PREF.get();
        modelChoice.setEditable(true);
        modelChoice.setValue(savedModel == null || savedModel.isBlank() ? MODEL_DEFAULT : savedModel);
        modelLabel.getStyleClass().add("label");
        updateModelLabel();
        modelChoice.valueProperty().addListener((obs, oldV, newV) -> {
            MODEL_PREF.set(newV == null ? MODEL_DEFAULT : newV);
            updateModelLabel();
        });

        var top = new VBox(4,
                new HBox(8, includeContext, claudePath),
                new HBox(8, new javafx.scene.control.Label("Model:"), modelChoice, modelLabel));
        HBox.setHgrow(claudePath, Priority.ALWAYS);
        var bottom = new VBox(6, new HBox(6, attach, skillFolder, exportMd), new HBox(6, input, send), openScript,
                new javafx.scene.control.Label("Research use only - not a validated diagnostic tool."));
        var root = new BorderPane(output);
        root.setTop(top);
        root.setBottom(bottom);
        BorderPane.setMargin(output, new Insets(8, 0, 8, 0));
        root.setPadding(new Insets(10));
        setScene(new Scene(root, 700, 550));
    }

    private void send() {
        String prompt = input.getText().trim();
        if (prompt.isEmpty())
            return;
        input.clear();
        send.setDisable(true);
        lastPrompt = prompt;
        output.appendText("\n> " + prompt + "\n\n");
        String full = includeContext.isSelected() ? buildContext() + "\nREQUEST\n" + prompt : prompt;
        final ImageJob job = attach.getValue().equals(ATTACH_NONE) ? null : captureImageJob(attach.getValue());
        if (!attach.getValue().equals(ATTACH_NONE) && job == null) {
            output.appendText("[no image or region to attach]\n");
            send.setDisable(false);
            return;
        }

        Thread t = new Thread(() -> {
            try {
                String text = full;
                if (job != null) {
                    var png = writeImage(job);
                    text += "\n\nATTACHED IMAGE (" + job.label() + ", downsample " + String.format("%.2f", job.downsample())
                            + "): " + png + "\nRead this image file, then follow the qupath-pathology skill.";
                    append("[attached " + job.label() + "]\n");
                }
                runClaude(text);
            } catch (Exception ex) {
                append("\n[error] " + ex.getMessage() + "\n");
            } finally {
                Platform.runLater(() -> send.setDisable(false));
            }
        }, "claude-cli");
        t.setDaemon(true);
        t.start();
    }

    /** Gather a description of the live image for the prompt. */
    private String buildContext() {
        var sb = new StringBuilder("CONTEXT\n");
        var project = qupath.getProject();
        if (project != null)
            sb.append("Project: ").append(project.getName()).append(" (")
              .append(project.getImageList().size()).append(" images)\n");
        var imageData = qupath.getImageData();
        if (imageData == null)
            return sb.append("No image open.\n").toString();
        var server = imageData.getServer();
        var hier = imageData.getHierarchy();
        var cal = server.getPixelCalibration();
        sb.append("Image: ").append(server.getMetadata().getName())
          .append(", ").append(server.getWidth()).append("x").append(server.getHeight()).append(" px")
          .append(", type=").append(imageData.getImageType())
          .append(", channels=").append(server.nChannels())
          .append(", pixel size=").append(cal.hasPixelSizeMicrons()
                  ? cal.getAveragedPixelSizeMicrons() + " um" : "unknown").append("\n");

        var annotations = hier.getAnnotationObjects();
        var detections = hier.getDetectionObjects();
        sb.append("Annotations: ").append(annotations.size()).append(" ")
          .append(countByClass(annotations)).append("\n");
        sb.append("Detections: ").append(detections.size()).append(" ")
          .append(countByClass(detections)).append("\n");
        sb.append("Annotation measurements: ").append(measurementNames(annotations)).append("\n");
        sb.append("Detection measurements: ").append(measurementNames(detections)).append("\n");

        var selected = hier.getSelectionModel().getSelectedObjects();
        sb.append("Selected objects: ").append(selected.size()).append("\n");
        int shown = 0;
        for (var o : selected) {
            if (shown++ >= 5) {
                sb.append("  ...\n");
                break;
            }
            sb.append("  - ").append(o.getClass().getSimpleName())
              .append(" name=").append(o.getName())
              .append(" class=").append(o.getPathClass());
            var roi = o.getROI();
            if (roi != null)
                sb.append(" roi=").append(roi.getRoiName()).append(" bounds=[")
                  .append((int) roi.getBoundsX()).append(",").append((int) roi.getBoundsY()).append(",")
                  .append((int) roi.getBoundsWidth()).append("x").append((int) roi.getBoundsHeight()).append("]");
            sb.append(" children=").append(o.nChildObjects()).append("\n");
        }
        return sb.toString();
    }

    private static String countByClass(java.util.Collection<? extends qupath.lib.objects.PathObject> objs) {
        var counts = new java.util.TreeMap<String, Integer>();
        for (var o : objs)
            counts.merge(String.valueOf(o.getPathClass()), 1, Integer::sum);
        return counts.toString();
    }

    /** Measurement names from a sample of objects (capped to keep the prompt small). */
    private static String measurementNames(java.util.Collection<? extends qupath.lib.objects.PathObject> objs) {
        var names = new java.util.LinkedHashSet<String>();
        int n = 0;
        for (var o : objs) {
            names.addAll(o.getMeasurementList().getNames());
            if (++n >= 50)
                break;
        }
        var list = new ArrayList<>(names);
        if (list.size() > 60)
            return list.subList(0, 60) + " ... (" + list.size() + " total)";
        return list.toString();
    }

    private void runClaude(String prompt) throws IOException, InterruptedException {
        String cmd = CLAUDE_CMD.get().trim();
        java.nio.file.Path promptFile = systemPromptFile();
        String model = modelChoice.getValue();
        String modelArgs = (model == null || model.isBlank() || model.equals(MODEL_DEFAULT))
                ? "" : " --model '" + model.replace("'", "'\\''") + "'";
        List<String> args = new ArrayList<>();
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        if (windows) {
            args.addAll(List.of("cmd", "/c", cmd, "-p", "--append-system-prompt-file", promptFile.toString(),
                    "--allowedTools", "Read,Glob,Grep", "--add-dir", TMP_DIR.toString()));
            if (!modelArgs.isEmpty())
                args.addAll(List.of("--model", model));
        } else {
            // GUI apps on macOS/Linux often lack the user's PATH; use the user's own login shell
            String shell = System.getenv("SHELL");
            if (shell == null || shell.isBlank())
                shell = "/bin/sh";
            args.addAll(List.of(shell, "-lc", cmd + " -p --append-system-prompt-file '" + promptFile + "'"
                    + " --allowedTools Read,Glob,Grep --add-dir '" + TMP_DIR + "'" + modelArgs));
        }
        var pb = new ProcessBuilder(args).redirectErrorStream(true);
        if (!windows) {
            // Fallback for common install locations (native installer, Homebrew, npm)
            String home = System.getProperty("user.home");
            String extra = home + "/.local/bin:" + home + "/.claude/local:/opt/homebrew/bin:/usr/local/bin";
            String path = pb.environment().getOrDefault("PATH", "/usr/bin:/bin");
            pb.environment().put("PATH", extra + ":" + path);
        }
        var project = qupath.getProject();
        File dir = project != null && project.getPath() != null
                ? project.getPath().getParent().toFile()
                : new File(System.getProperty("user.home"));
        pb.directory(dir);
        Process p = pb.start();
        try (var os = p.getOutputStream()) {
            os.write(prompt.getBytes(StandardCharsets.UTF_8));
        }
        var response = new StringBuilder();
        try (var r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                response.append(line).append('\n');
                append(line + "\n");
            }
        }
        int code = p.waitFor();
        if (code != 0)
            append("\n[claude exited with code " + code + "]\n");

        lastResponse = response.toString();
        Platform.runLater(() -> exportMd.setDisable(false));

        Matcher m = GROOVY_BLOCK.matcher(response);
        String last = null;
        while (m.find())
            last = m.group(1);
        if (last != null) {
            lastScript = last;
            Platform.runLater(() -> openScript.setDisable(false));
        }
    }

    private void updateModelLabel() {
        String v = modelChoice.getValue();
        modelLabel.setText(v == null || v.isBlank() || v.equals(MODEL_DEFAULT)
                ? "using the claude CLI's own default model" : "will run as: " + v);
    }

    /** Write the most recent response to a timestamped .md file in the export folder. */
    private void exportMarkdown() {
        String response = lastResponse;
        if (response == null || response.isBlank()) {
            output.appendText("[nothing to export yet]\n");
            return;
        }
        try {
            var dir = java.nio.file.Path.of(EXPORT_DIR_PREF.get());
            java.nio.file.Files.createDirectories(dir);
            String stamp = java.time.LocalDateTime.now().format(FILE_STAMP);
            var file = dir.resolve("claude-" + stamp + ".md");

            var sb = new StringBuilder();
            sb.append("# Claude Code note\n\n");
            sb.append("- Date: ").append(java.time.LocalDateTime.now()).append("\n");
            sb.append("- Model: ").append(modelChoice.getValue()).append("\n");
            var imageData = qupath.getImageData();
            if (imageData != null)
                sb.append("- Image: ").append(imageData.getServer().getMetadata().getName()).append("\n");
            sb.append("- Research use only - not a validated diagnostic tool.\n\n");
            if (lastPrompt != null)
                sb.append("## Prompt\n\n").append(lastPrompt).append("\n\n");
            sb.append("## Response\n\n").append(response).append("\n");

            java.nio.file.Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            output.appendText("[exported to " + file + "]\n");
        } catch (IOException ex) {
            output.appendText("[export failed] " + ex.getMessage() + "\n");
        }
    }

    private static final java.nio.file.Path TMP_DIR = createTmpDir();

    private static java.nio.file.Path createTmpDir() {
        try {
            var d = java.nio.file.Files.createTempDirectory("qupath-claude-");
            d.toFile().deleteOnExit();
            return d;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private record ImageJob(qupath.lib.images.servers.ImageServer<java.awt.image.BufferedImage> server,
                            int x, int y, int w, int h, double downsample, String label) {}

    /** Work out the region to export (must run on the FX thread). */
    private ImageJob captureImageJob(String mode) {
        var viewer = qupath.getViewer();
        var server = viewer == null ? null : viewer.getServer();
        if (server == null)
            return null;
        java.awt.Rectangle r;
        String label;
        if (mode.equals(ATTACH_SELECTION)) {
            var sel = viewer.getSelectedObject();
            if (sel == null || sel.getROI() == null)
                return null;
            var roi = sel.getROI();
            r = new java.awt.Rectangle((int) roi.getBoundsX(), (int) roi.getBoundsY(),
                    Math.max(1, (int) Math.ceil(roi.getBoundsWidth())), Math.max(1, (int) Math.ceil(roi.getBoundsHeight())));
            label = "selected region " + r.width + "x" + r.height + " px at (" + r.x + "," + r.y + ")";
        } else {
            r = viewer.getDisplayedRegionShape().getBounds();
            label = "viewport " + r.width + "x" + r.height + " px at (" + r.x + "," + r.y + ")";
        }
        r = r.intersection(new java.awt.Rectangle(0, 0, server.getWidth(), server.getHeight()));
        if (r.isEmpty())
            return null;
        double ds = Math.max(1.0, Math.max(r.width, r.height) / (double) MAX_IMAGE_SIDE);
        return new ImageJob(server, r.x, r.y, r.width, r.height, ds, label);
    }

    private static java.nio.file.Path writeImage(ImageJob j) throws IOException {
        var img = j.server().readRegion(j.downsample(), j.x(), j.y(), j.w(), j.h());
        var f = java.nio.file.Files.createTempFile(TMP_DIR, "region-", ".png");
        javax.imageio.ImageIO.write(img, "png", f.toFile());
        f.toFile().deleteOnExit();
        return f;
    }

    /** Open (creating from a template if needed) the folder holding the pathology skill. */
    private void openSkillFolder() {
        try {
            var dir = java.nio.file.Path.of(System.getProperty("user.home"), ".claude", "skills", "qupath-pathology");
            if (!java.nio.file.Files.exists(dir.resolve("SKILL.md"))) {
                java.nio.file.Files.createDirectories(dir.resolve("references"));
                java.nio.file.Files.createDirectories(dir.resolve("examples"));
                java.nio.file.Files.write(dir.resolve("SKILL.md"), readResource("skill-template.md"));
            }
            java.awt.Desktop.getDesktop().open(dir.toFile());
        } catch (Exception ex) {
            output.appendText("[could not open skill folder] " + ex.getMessage() + "\n");
        }
    }

    /**
     * Load a bundled resource. QuPath's extension class loader doesn't always resolve resources through
     * Class.getResourceAsStream, so fall back to other loaders and finally to reading the jar directly.
     */
    private static byte[] readResource(String name) throws IOException {
        String full = "qupath/ext/claude/" + name;
        var candidates = new java.util.ArrayList<java.util.function.Supplier<java.io.InputStream>>();
        candidates.add(() -> ClaudeStage.class.getResourceAsStream(name));
        candidates.add(() -> ClaudeStage.class.getClassLoader().getResourceAsStream(full));
        candidates.add(() -> Thread.currentThread().getContextClassLoader().getResourceAsStream(full));
        for (var c : candidates) {
            try (var in = c.get()) {
                if (in != null)
                    return in.readAllBytes();
            } catch (Exception ignored) {
            }
        }
        try {
            var loc = ClaudeStage.class.getProtectionDomain().getCodeSource().getLocation();
            try (var zip = new java.util.zip.ZipFile(new File(loc.toURI()))) {
                var entry = zip.getEntry(full);
                if (entry != null)
                    try (var in = zip.getInputStream(entry)) {
                        return in.readAllBytes();
                    }
            }
        } catch (Exception e) {
            throw new IOException("Could not load bundled resource " + name + ": " + e, e);
        }
        throw new IOException("Bundled resource not found: " + name);
    }

    private static java.nio.file.Path systemPromptFile() throws IOException {
        var f = java.nio.file.Files.createTempFile("qupath-claude-system", ".md");
        f.toFile().deleteOnExit();
        java.nio.file.Files.write(f, readResource("system-prompt.md"));
        return f;
    }

    private void openInEditor() {
        if (lastScript == null)
            return;
        ScriptEditor editor = qupath.getScriptEditor();
        if (editor != null)
            editor.showScript("Claude script", lastScript);
    }

    private void append(String s) {
        Platform.runLater(() -> output.appendText(s));
    }
}
