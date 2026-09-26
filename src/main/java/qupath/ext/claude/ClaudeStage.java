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
        String full = includeContext.isSelected() ? buildContext() + "\n\n" + prompt : prompt;

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

    /** Gather context on the GUI thread-safe-ish (read-only) state. */
    private String buildContext() {
        var sb = new StringBuilder();
        sb.append("You are helping inside QuPath (digital pathology). ")
          .append("When asked for code, reply with a single ```groovy block using the QuPath scripting API.\n");
        var project = qupath.getProject();
        if (project != null)
            sb.append("Project: ").append(project.getName()).append(" (")
              .append(project.getImageList().size()).append(" images)\n");
        var imageData = qupath.getImageData();
        if (imageData != null) {
            var server = imageData.getServer();
            var hier = imageData.getHierarchy();
            sb.append("Image: ").append(server.getMetadata().getName())
              .append(", ").append(server.getWidth()).append("x").append(server.getHeight())
              .append(", type=").append(imageData.getImageType())
              .append(", pixelSize=").append(server.getPixelCalibration().getAveragedPixelSize()).append("\n");
            sb.append("Annotations: ").append(hier.getAnnotationObjects().size())
              .append(", detections: ").append(hier.getDetectionObjects().size())
              .append(", selected: ").append(hier.getSelectionModel().getSelectedObjects().size()).append("\n");
        } else {
            sb.append("No image open.\n");
        }
        return sb.toString();
    }

    private void runClaude(String prompt) throws IOException, InterruptedException {
        String cmd = CLAUDE_CMD.get().trim();
        List<String> args = new ArrayList<>();
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        if (windows) {
            args.addAll(List.of("cmd", "/c", cmd, "-p"));
        } else {
            // GUI apps on macOS/Linux often lack the user's PATH; use a login shell
            args.addAll(List.of("/bin/sh", "-lc", cmd + " -p"));
        }
        var pb = new ProcessBuilder(args).redirectErrorStream(true);
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
