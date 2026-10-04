package ani.rss.entity;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
public class PathReplaceRule implements Serializable {
    private String from;
    private String to;
}
