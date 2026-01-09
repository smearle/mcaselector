package net.querz.mcaselector.ui.dialog;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import net.querz.mcaselector.text.Translation;
import net.querz.mcaselector.ui.UIFactory;

/**
 * Dialog for configuring tile JSON export options.
 */
public class ExportTilesJsonDialog extends Dialog<ExportTilesJsonDialog.Result> {

	private final Spinner<Integer> minYSpinner;
	private final Spinner<Integer> maxYSpinner;
	private final CheckBox includeAirCheckBox;
	private final CheckBox compressCheckBox;

	public ExportTilesJsonDialog(Stage primaryStage) {
		setTitle(Translation.DIALOG_EXPORT_TILES_JSON_TITLE.toString());
		initOwner(primaryStage);

		getDialogPane().getStyleClass().add("export-tiles-json-dialog-pane");

		setHeaderText(Translation.DIALOG_EXPORT_TILES_JSON_HEADER.toString());

		// Min Y spinner (-64 to 319 for modern worlds)
		minYSpinner = new Spinner<>(-64, 319, -64);
		minYSpinner.setEditable(true);
		minYSpinner.setPrefWidth(100);

		// Max Y spinner
		maxYSpinner = new Spinner<>(-64, 319, 319);
		maxYSpinner.setEditable(true);
		maxYSpinner.setPrefWidth(100);

		// Include air checkbox
		includeAirCheckBox = new CheckBox();
		includeAirCheckBox.setSelected(false);

		// Compress output checkbox
		compressCheckBox = new CheckBox();
		compressCheckBox.setSelected(false);

		// Layout
		GridPane grid = new GridPane();
		grid.setHgap(10);
		grid.setVgap(10);
		grid.setPadding(new Insets(20, 10, 10, 10));

		Label minYLabel = UIFactory.label(Translation.DIALOG_EXPORT_TILES_JSON_OPTIONS_MIN_Y);
		Label maxYLabel = UIFactory.label(Translation.DIALOG_EXPORT_TILES_JSON_OPTIONS_MAX_Y);
		Label includeAirLabel = UIFactory.label(Translation.DIALOG_EXPORT_TILES_JSON_OPTIONS_INCLUDE_AIR);
		Label compressLabel = UIFactory.label(Translation.DIALOG_EXPORT_TILES_JSON_OPTIONS_COMPRESS);

		grid.add(minYLabel, 0, 0);
		grid.add(minYSpinner, 1, 0);
		grid.add(maxYLabel, 0, 1);
		grid.add(maxYSpinner, 1, 1);
		grid.add(includeAirLabel, 0, 2);
		grid.add(includeAirCheckBox, 1, 2);
		grid.add(compressLabel, 0, 3);
		grid.add(compressCheckBox, 1, 3);

		getDialogPane().setContent(grid);

		// Buttons
		getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

		setResultConverter(buttonType -> {
			if (buttonType == ButtonType.OK) {
				return new Result(
						minYSpinner.getValue(),
						maxYSpinner.getValue(),
						includeAirCheckBox.isSelected(),
						compressCheckBox.isSelected()
				);
			}
			return null;
		});
	}

	public record Result(int minY, int maxY, boolean includeAir, boolean compress) {}
}
