package io.hl7sender.app;

import io.hl7sender.core.hl7.Delimiters;
import io.hl7sender.core.hl7.FieldDictionary;
import io.hl7sender.core.hl7.ParsedMessage;
import io.hl7sender.core.hl7.Segment;
import java.util.List;
import javafx.scene.control.TreeItem;

/**
 * Builds a segment → field → repetition → component → subcomponent tree for display. Empty fields
 * are left out. A level is only expanded when it actually contains separators.
 */
final class MessageTree {

    private MessageTree() {
    }

    static TreeItem<String> build(ParsedMessage message) {
        TreeItem<String> root = new TreeItem<>("Message");
        root.setExpanded(true);
        Delimiters d = message.delimiters();
        String version = message.header().version();
        FieldDictionary names = FieldDictionary.shared();
        int index = 0;
        for (Segment segment : message.segments()) {
            index++;
            TreeItem<String> segItem = new TreeItem<>(segment.name() + "  (segment " + index + ")");
            for (int f = 1; f <= segment.fieldCount(); f++) {
                String value = segment.field(f);
                if (value.isEmpty()) {
                    continue;
                }
                int fieldNo = f;
                String label = segment.name() + "-" + f + names.field(version, segment.name(), f)
                        .map(info -> "  " + info.name()).orElse("");
                boolean delimiterField = isHeader(segment) && fieldNo <= 2;
                segItem.getChildren().add(delimiterField ? leaf(label, value) : field(label, value, d));
            }
            root.getChildren().add(segItem);
        }
        if (!root.getChildren().isEmpty()) {
            root.getChildren().get(0).setExpanded(true);
        }
        return root;
    }

    private static TreeItem<String> field(String label, String value, Delimiters d) {
        List<String> reps = Segment.split(value, d.repetition());
        if (reps.size() == 1) {
            return components(label, value, d);
        }
        TreeItem<String> item = leaf(label, value);
        for (int r = 0; r < reps.size(); r++) {
            if (!reps.get(r).isEmpty()) {
                item.getChildren().add(components(label + "[" + (r + 1) + "]", reps.get(r), d));
            }
        }
        return item;
    }

    private static TreeItem<String> components(String label, String value, Delimiters d) {
        TreeItem<String> item = leaf(label, value);
        List<String> comps = Segment.split(value, d.component());
        if (comps.size() == 1) {
            addSubcomponents(item, label, value, d);
            return item;
        }
        for (int c = 0; c < comps.size(); c++) {
            if (!comps.get(c).isEmpty()) {
                String compLabel = label + "." + (c + 1);
                TreeItem<String> comp = leaf(compLabel, comps.get(c));
                addSubcomponents(comp, compLabel, comps.get(c), d);
                item.getChildren().add(comp);
            }
        }
        return item;
    }

    private static void addSubcomponents(TreeItem<String> parent, String label, String value, Delimiters d) {
        List<String> subs = Segment.split(value, d.subcomponent());
        if (subs.size() == 1) {
            return;
        }
        for (int s = 0; s < subs.size(); s++) {
            if (!subs.get(s).isEmpty()) {
                parent.getChildren().add(leaf(label + "." + (s + 1), subs.get(s)));
            }
        }
    }

    private static TreeItem<String> leaf(String label, String value) {
        return new TreeItem<>(label + "  =  " + value);
    }

    private static boolean isHeader(Segment s) {
        return "MSH".equals(s.name()) || "BHS".equals(s.name()) || "FHS".equals(s.name());
    }
}
