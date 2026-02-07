package ru.dto.exchanges.extended;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtendedTrade {
    private String i;  // trade id
    private String m;  // market
    private String S;  // side
    private String p;  // price
    private String q;  // quantity
    private Long T;    // timestamp
}
