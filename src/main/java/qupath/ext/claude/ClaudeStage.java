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
    private String lastScript;

    private static final javafx.beans.property.StringProperty CLAUDE_CMD =
            PathPrefs.createPersistentPreference("ext.claude.command", "claude");

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

        var top = new HBox(8, includeContext, claudePath);
        HBox.setHgrow(claudePath, Priority.ALWAYS);
        var bottom = new VBox(6, new HBox(6, input, send), openScript);
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
        output.appendText("\n> " + prompt + "\n\n");
        String full = includeContext.isSelected() ? buildContext() + "\nREQUEST\n" + prompt : prompt;

        Thread t = new Thread(() -> {
            try {
                runClaude(full);
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
        List<String> args = new ArrayList<>();
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        if (windows) {
            args.addAll(List.of("cmd", "/c", cmd, "-p", "--append-system-prompt-file", promptFile.toString()));
        } else {
            // GUI apps on macOS/Linux often lack the user's PATH; use the user's own login shell
            String shell = System.getenv("SHELL");
            if (shell == null || shell.isBlank())
                shell = "/bin/sh";
            args.addAll(List.of(shell, "-lc", cmd + " -p --append-system-prompt-file '" + promptFile + "'"));
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

        Matcher m = GROOVY_BLOCK.matcher(response);
        String last = null;
        while (m.find())
            last = m.group(1);
        if (last != null) {
            lastScript = last;
            Platform.runLater(() -> openScript.setDisable(false));
        }
    }

    private static java.nio.file.Path systemPromptFile() throws IOException {
        var f = java.nio.file.Files.createTempFile("qupath-claude-system", ".md");
        f.toFile().deleteOnExit();
        try (var in = ClaudeStage.class.getResourceAsStream("system-prompt.md")) {
            java.nio.file.Files.write(f, in.readAllBytes());
        }
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
