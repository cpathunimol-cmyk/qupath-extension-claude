package qupath.ext.claude;

import javafx.scene.control.MenuItem;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

public class ClaudeExtension implements QuPathExtension {

    private ClaudeStage stage;

    @Override
    public void installExtension(QuPathGUI qupath) {
        var item = new MenuItem("Claude Code...");
        item.setOnAction(e -> {
            if (stage == null)
                stage = new ClaudeStage(qupath);
            stage.show();
            stage.toFront();
        });
        qupath.getMenu("Extensions>Claude", true).getItems().add(item);
    }

    @Override
    public String getName() {
        return "Claude Code";
    }

    @Override
    public String getDescription() {
        return "Ask Claude Code about the current image/project and generate Groovy scripts.";
    }
}
