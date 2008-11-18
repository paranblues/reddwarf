/*
 * Copyright 2008 Sun Microsystems, Inc.
 *
 * This file is part of the Darkstar Test Cluster
 *
 * Darkstar Test Cluster is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation and
 * distributed hereunder to you.
 *
 * Darkstar Test Cluster is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.projectdarkstar.tools.dtc.data;

import com.projectdarkstar.tools.dtc.exceptions.DTCInvalidDataException;
import org.apache.commons.lang.ObjectUtils;

/**
 * Represents a property by mapping a property name to a value.
 */
public class PropertyDTO extends AbstractDTO
{
    private Long id;
    private Long versionNumber;
    private String description;
    private String property;
    private String value;
    

    public PropertyDTO(Long id,
                       Long versionNumber,
                       String description,
                       String property,
                       String value)
    {
        this.setId(id);
        this.setVersionNumber(versionNumber);
        
        this.setDescription(description);
        this.setProperty(property);
        this.setValue(value);
    }
    
    /**
     * Returns the id of the entity in persistent storage
     * 
     * @return id of the entity
     */
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    /**
     * Returns the version number in the data store that this entity represents.
     * Whenever an update to an object is pushed to the persistent data
     * store, the version number is incremented.
     * 
     * @return version number of the entity
     */
    public Long getVersionNumber() { return versionNumber; }
    private void setVersionNumber(Long versionNumber) { this.versionNumber = versionNumber; }
    
    public String getDescription() { return description; }
    protected void setDescription(String description) { this.description = description; }
    public void updateDescription(String description)
            throws DTCInvalidDataException {
        this.updateAttribute("description", description);
    }
    
    public String getProperty() { return property; }
    protected void setProperty(String property) { this.property = property; }
    public void updateProperty(String property)
            throws DTCInvalidDataException {
        this.updateAttribute("property", property);
    }
    
    public String getValue() { return value; }
    protected void setValue(String value) { this.value = value; }
    public void updateValue(String value)
            throws DTCInvalidDataException {
        this.updateAttribute("value", value);
    }
    
    /** @inheritDoc */
    public void validate() throws DTCInvalidDataException
    {
        this.checkNull("description");
        this.checkBlank("property");
        this.checkNull("value");
    }
    
    public boolean equals(Object o) {
        if(this == o) return true;
	if(!(o instanceof PropertyDTO) || o == null) return false;

        PropertyDTO other = (PropertyDTO)o;
        return ObjectUtils.equals(this.getId(), other.getId()) &&
                ObjectUtils.equals(this.getVersionNumber(), other.getVersionNumber()) &&
                ObjectUtils.equals(this.getDescription(), other.getDescription()) &&
                ObjectUtils.equals(this.getProperty(), other.getProperty()) &&
                ObjectUtils.equals(this.getValue(), other.getValue());
    }
    
    public int hashCode() {
        int hash = 7;
        int hashId = 31*hash + ObjectUtils.hashCode(this.getId());
        int hashProperty = 31*hash + ObjectUtils.hashCode(this.getProperty());
        return hashId + hashProperty;
    }
}