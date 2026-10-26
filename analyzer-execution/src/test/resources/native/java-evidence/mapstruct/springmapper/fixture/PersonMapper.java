package fixture;
@org.mapstruct.Mapper(componentModel = "spring")
public interface PersonMapper {
    Output map(Input source);
}
