package de.telekom.eni.pandora.horizon.model.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

@Getter
@Setter
@RequiredArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProblemMessage {

    @NonNull
    private final String type;

    @NonNull
    private final String title;

    private int status;

    private String detail;

    private String instance;
}
