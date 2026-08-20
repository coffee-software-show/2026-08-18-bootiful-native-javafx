package com.example.bootiful_javafx;

import javafx.stage.Stage;
import org.springframework.context.ApplicationEvent;

class StageReadyEvent extends ApplicationEvent {

	public StageReadyEvent(Stage stage) {
		super(stage);
	}

	public Stage stage() {
		return (Stage) getSource();
	}

}
