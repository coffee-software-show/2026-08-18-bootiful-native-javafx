package com.example.bootiful_javafx;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
class SystemBrowser implements AuthorizationBrowser {

	@Override
	public void open(String authorizationRequestUri) {
		try {
			var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			var mapping = Map.of(//
					"mac", List.of("open", authorizationRequestUri), //
					"win", List.of("rundll32", "url.dll,FileProtocolHandler", authorizationRequestUri)//
			);
			var command = List.of("xdg-open", authorizationRequestUri);
			for (var osKey : mapping.keySet())
				if (os.contains(osKey))
					command = mapping.get(osKey);
			new ProcessBuilder(command).start();
		} //
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}
