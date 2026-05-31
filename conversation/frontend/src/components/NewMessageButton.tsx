import { Button } from '@open-ent/react';
import { IconEdit } from '@open-ent/react/icons';
import { useNavigate } from 'react-router-dom';
import { useI18n } from '~/hooks/useI18n';

export const NewMessageButton = () => {
  const { t } = useI18n();
  const navigate = useNavigate();

  const handleCreateNewMessage = () => {
    navigate('/draft/create');
  };

  return (
    <Button
      leftIcon={<IconEdit />}
      onClick={handleCreateNewMessage}
      className="text-nowrap"
    >
      {t('new.message')}
    </Button>
  );
};
