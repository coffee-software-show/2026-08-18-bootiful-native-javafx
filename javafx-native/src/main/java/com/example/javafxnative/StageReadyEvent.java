package com.example.javafxnative;

import javafx.stage.Stage;
import org.springframework.context.ApplicationEvent;

/** Published when JavaFX has a primary {@link Stage} ready for the Spring beans to decorate. */
public class StageReadyEvent extends ApplicationEvent {

	public StageReadyEvent(Stage stage) {
		super(stage);
	}

	public Stage stage() {
		return (Stage) getSource();
	}

}
